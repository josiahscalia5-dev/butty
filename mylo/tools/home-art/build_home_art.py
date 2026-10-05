#!/usr/bin/env python3
"""Builds Home's artwork from the approved Home target (design/reference/home-reference.jpg, 864 × 1536 px).

The target has its words and controls baked into the illustration. Mylo draws those natively (sharp at every
density, readable by TalkBack, the greeting and Shield state always real), so this script lifts the two
illustrations out of the target and removes everything that is not artwork:

  home_hero.webp       the night scene with Mylo on the moon and the star (target rows 0–447), without the status
                       bar, greeting, settings button, wordmark or tagline
  home_discovery.webp  the "A little more wonder" lake scene (inside the banner's border), without its title,
                       "Explore today" or the arrow button

Both keep the target's exact crop and are upscaled ×SCALE so they stay smooth on dense screens. The page code
places them by the same target coordinates (see HomeScreen.kt, `Ref`).
Requires Python 3 with numpy, Pillow and opencv-python-headless.

  python3 tools/home-art/build_home_art.py [--debug OUT_DIR]
"""
import argparse
import os

import cv2
import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REFERENCE = os.path.join(ROOT, "design", "reference", "home-reference.jpg")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
HERO_BOTTOM = 448  # target px: the search field starts here
BANNER = (36, 1205, 829, 1374)  # target px, inside the banner's 2 px border
SCALE = 2


def luminance(rgb):
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def in_box(shape, box):
    m = np.zeros(shape[:2], bool)
    x0, y0, x1, y1 = box
    m[y0:y1, x0:x1] = True
    return m


def dilate(mask, px):
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * px + 1, 2 * px + 1))
    return cv2.dilate(mask.astype(np.uint8) * 255, k) > 0


def hero_mask(rgb):
    """Status bar, greeting, settings button, wordmark and tagline: everything Mylo draws itself."""
    lum = luminance(rgb.astype(np.float32))
    r, g, b = (rgb[..., i].astype(np.int32) for i in range(3))
    shape = rgb.shape
    parts = []
    # Status bar: the clock on the left, signal/wifi/battery on the right.
    parts.append(dilate((lum > 95) & in_box(shape, (52, 12, 120, 44)), 3))
    parts.append(dilate((lum > 95) & in_box(shape, (690, 8, 810, 50)), 3))
    # Greeting: the moon disc, the two lines of text and the sparkles.
    disc = np.zeros(shape[:2], np.uint8)
    cv2.circle(disc, (83, 98), 42, 255, -1)
    parts.append(disc > 0)
    # The whole text block (the words carry a soft dark shadow), as a smooth patch of sky.
    parts.append(in_box(shape, (130, 62, 384, 132)))
    # Settings button.
    gear = np.zeros(shape[:2], np.uint8)
    cv2.circle(gear, (787, 95), 42, 255, -1)
    parts.append(gear > 0)
    # Wordmark (white "My", lavender "lo" and its smile) with its soft edge and drop shadow.
    lavender = (b > 170) & (b - r > 20) & (lum > 140)
    word = ((lum > 150) | lavender) & in_box(shape, (82, 152, 410, 300))
    word = dilate(word, 5)
    shadow = np.zeros_like(word)
    shadow[6:, :] = word[:-6, :]
    parts.append(word | shadow)
    # Tagline.
    parts.append(dilate((lum > 120) & in_box(shape, (96, 288, 398, 334)), 4))
    return np.max(np.stack(parts), axis=0)


def banner_mask(rgb):
    """Title, "Explore today" and the round arrow button, in banner-local coordinates."""
    lum = luminance(rgb.astype(np.float32))
    shape = rgb.shape
    parts = [
        dilate((lum > 150) & in_box(shape, (36, 18, 290, 98)), 4),
        dilate((lum > 120) & in_box(shape, (40, 108, 196, 146)), 3),
    ]
    button = np.zeros(shape[:2], np.uint8)
    cv2.circle(button, (229, 126), 24, 255, -1)
    parts.append(button > 0)
    return np.max(np.stack(parts), axis=0)


def inpaint(rgb, mask, radius):
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    filled = cv2.inpaint(bgr, mask.astype(np.uint8) * 255, radius, cv2.INPAINT_TELEA)
    # Soften the filled pixels a little more so smeared streaks read as sky and haze.
    blurred = cv2.GaussianBlur(filled, (0, 0), 2.2)
    soft = cv2.GaussianBlur(mask.astype(np.float32), (0, 0), 1.5)[..., None]
    out = filled * (1 - soft) + blurred * soft
    return cv2.cvtColor(out.clip(0, 255).astype(np.uint8), cv2.COLOR_BGR2RGB)


def upscale(rgb):
    h, w = rgb.shape[:2]
    big = cv2.resize(rgb, (w * SCALE, h * SCALE), interpolation=cv2.INTER_LANCZOS4)
    image = Image.fromarray(big).filter(ImageFilter.UnsharpMask(radius=1.6, percent=55, threshold=2))
    return image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", help="also write masks and before/after images here")
    args = parser.parse_args()
    target = np.asarray(Image.open(REFERENCE).convert("RGB"))
    assert target.shape[:2] == (1536, 864), target.shape

    hero = target[:HERO_BOTTOM].copy()
    hmask = hero_mask(hero)
    hero_clean = inpaint(hero, hmask, 9)

    x0, y0, x1, y1 = BANNER
    banner = target[y0:y1, x0:x1].copy()
    bmask = banner_mask(banner)
    banner_clean = inpaint(banner, bmask, 7)

    upscale(hero_clean).save(os.path.join(OUT, "home_hero.webp"), "WEBP", quality=93, method=6)
    upscale(banner_clean).save(os.path.join(OUT, "home_discovery.webp"), "WEBP", quality=93, method=6)

    if args.debug:
        os.makedirs(args.debug, exist_ok=True)
        Image.fromarray((hmask * 255).astype(np.uint8)).save(os.path.join(args.debug, "hero-mask.png"))
        Image.fromarray(hero_clean).save(os.path.join(args.debug, "hero-clean.png"))
        Image.fromarray((bmask * 255).astype(np.uint8)).save(os.path.join(args.debug, "banner-mask.png"))
        Image.fromarray(banner_clean).save(os.path.join(args.debug, "banner-clean.png"))
    print("home_hero.webp", hero_clean.shape[1] * SCALE, "×", hero_clean.shape[0] * SCALE)
    print("home_discovery.webp", banner_clean.shape[1] * SCALE, "×", banner_clean.shape[0] * SCALE)


if __name__ == "__main__":
    main()
