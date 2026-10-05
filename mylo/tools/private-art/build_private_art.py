#!/usr/bin/env python3
"""Builds the Private Mode artwork from the approved reference (design/reference/private-mode-reference.png).

The reference has its words baked into the illustration. Mylo draws those words natively (sharp at every
density, readable by TalkBack), so this script removes them from the art and separates the corgi so it can
settle onto the moon when Private Mode opens:

  private_hero_scene.webp   the night scene and moon, with the text, status bar, gear and corgi removed
  private_hero_corgi.webp   the sunglasses corgi on transparency, registered to the scene
  private_hero_lenses.webp  an alpha mask of the sunglasses lenses, for the one-time highlight

All three share one coordinate space: the reference's top 480 px (941 px wide), upscaled ×SCALE.
Requires Python 3 with numpy, Pillow and opencv-python-headless.

  python3 tools/private-art/build_private_art.py [--debug OUT_DIR]
"""
import argparse
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REFERENCE = os.path.join(ROOT, "design", "reference", "private-mode-reference.png")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
HERO_HEIGHT = 480  # reference px; the status card starts at 471
SCALE = 2
SEED = 7


def luminance(rgb):
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def box_mask(shape, box):
    m = np.zeros(shape[:2], np.uint8)
    x0, y0, x1, y1 = box
    m[y0:y1, x0:x1] = 255
    return m


def dilate(mask, px):
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * px + 1, 2 * px + 1))
    return cv2.dilate(mask, k)


def text_masks(rgb):
    """Pixels of the baked-in words, status bar and gear, each region with its own colour test."""
    lum = luminance(rgb.astype(np.float32))
    r, g, b = (rgb[..., i].astype(np.int32) for i in range(3))
    shape = rgb.shape
    masks = []
    # Status bar: time on the left, network and battery on the right (white).
    for box in [(40, 8, 150, 52), (750, 8, 890, 52)]:
        masks.append(dilate(((lum > 110) & (box_mask(shape, box) > 0)).astype(np.uint8) * 255, 3))
    # Gear button: a translucent disc with a white gear.
    gear = np.zeros(shape[:2], np.uint8)
    cv2.circle(gear, (868, 106), 43, 255, -1)
    masks.append(gear)
    # Wordmark, "Private Mode" and "Browse without a trace." (white and lavender, with a soft glow).
    for box, threshold, grow in [((55, 78, 340, 202), 78, 7), ((55, 203, 476, 272), 72, 6), ((55, 273, 446, 316), 72, 5)]:
        bright = (lum > threshold) & (box_mask(shape, box) > 0)
        masks.append(dilate(bright.astype(np.uint8) * 255, grow))
    # Handwriting over the lake and on the right: lavender-white strokes (never the warm lake lights).
    lavender = (lum > 105) & (b > g + 8) & (b >= r - 10)
    for box in [(60, 322, 300, 418), (810, 180, 930, 345)]:
        masks.append(dilate((lavender & (box_mask(shape, box) > 0)).astype(np.uint8) * 255, 3))
    return np.max(np.stack(masks), axis=0)


def corgi_mask(rgb):
    """The corgi (head, sunglasses, hoodie, paws, tail) separated from the moon and sky with GrabCut."""
    h, w = rgb.shape[:2]
    mask = np.full((h, w), cv2.GC_BGD, np.uint8)
    # Probable foreground: the corgi's bounding region.
    cv2.rectangle(mask, (522, 62), (812, 418), cv2.GC_PR_FGD, -1)
    # Definite foreground strokes through the face, sunglasses, chest, hoodie, paws and tail.
    for poly in [
        [(600, 135), (650, 150), (700, 178), (740, 185), (720, 260), (620, 270), (575, 200)],
        [(560, 300), (640, 290), (730, 300), (720, 380), (600, 385)],
        [(740, 320), (770, 310), (790, 360), (760, 390)],
    ]:
        cv2.fillPoly(mask, [np.array(poly, np.int32)], cv2.GC_FGD)
    # Definite background: the moon's lit crescent and the open sky around the corgi.
    for poly in [
        [(470, 300), (520, 240), (540, 300), (560, 420), (640, 450), (800, 380), (830, 400), (760, 470), (560, 470), (480, 400)],
    ]:
        cv2.fillPoly(mask, [np.array(poly, np.int32)], cv2.GC_PR_BGD)
    cv2.rectangle(mask, (470, 40), (590, 110), cv2.GC_BGD, -1)  # sky left of the ears (excluding the star)
    cv2.rectangle(mask, (690, 40), (830, 128), cv2.GC_BGD, -1)  # sky above the right ear
    cv2.fillPoly(mask, [np.array([(676, 96), (712, 128), (716, 150), (690, 150), (672, 120)], np.int32)], cv2.GC_BGD)  # between the ears
    cv2.fillPoly(mask, [np.array([(750, 262), (790, 262), (790, 296), (758, 310), (750, 296)], np.int32)], cv2.GC_BGD)  # beside the hoodie
    cv2.fillPoly(mask, [np.array([(723, 302), (745, 298), (744, 322), (736, 330), (723, 327)], np.int32)], cv2.GC_BGD)  # hoodie-tail gap
    bgd = np.zeros((1, 65), np.float64)
    fgd = np.zeros((1, 65), np.float64)
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    cv2.grabCut(bgr, mask, None, bgd, fgd, 8, cv2.GC_INIT_WITH_MASK)
    fg = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
    # Keep the largest connected piece (the corgi), fill holes, then feather the edge slightly.
    count, labels, stats, _ = cv2.connectedComponentsWithStats(fg)
    if count > 1:
        largest = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
        fg = np.where(labels == largest, 255, 0).astype(np.uint8)
    contours, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    solid = np.zeros_like(fg)
    cv2.drawContours(solid, contours, -1, 255, -1)
    return solid


def lens_mask(rgb, corgi):
    """The two dark lenses of the sunglasses (inside the frame), for the highlight sweep."""
    lum = luminance(rgb.astype(np.float32))
    region = box_mask(rgb.shape, (556, 166, 732, 250))
    r, g, b = (rgb[..., i].astype(np.int32) for i in range(3))
    # Black glass, and the blue reflections on it.
    glass = (lum < 40) | ((b > r + 35) & (lum < 150))
    dark = (glass & (region > 0) & (corgi > 0)).astype(np.uint8) * 255
    dark = cv2.morphologyEx(dark, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(dark)
    keep = np.zeros_like(dark)
    # The two largest dark blobs are the lenses.
    order = sorted(range(1, count), key=lambda i: -stats[i, cv2.CC_STAT_AREA])[:2]
    for i in order:
        keep[labels == i] = 255
    return cv2.GaussianBlur(keep, (3, 3), 0)


def add_stars(rgb, filled, rng, density):
    """Sprinkles faint stars into inpainted sky so it keeps the reference's texture."""
    out = rgb.astype(np.float32)
    ys, xs = np.where(filled > 0)
    if len(xs) == 0:
        return rgb
    lum = luminance(out)
    candidates = rng.choice(len(xs), size=max(1, int(len(xs) * density)), replace=False)
    for i in candidates:
        x, y = xs[i], ys[i]
        if lum[y, x] > 70:  # only in dark sky
            continue
        strength = rng.uniform(0.35, 0.85)
        radius = rng.choice([0.6, 0.8, 1.1])
        tint = np.array([255, 236, 200]) if rng.random() < 0.35 else np.array([225, 228, 255])
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                yy, xx = y + dy, x + dx
                if 0 <= yy < out.shape[0] and 0 <= xx < out.shape[1]:
                    falloff = np.exp(-(dx * dx + dy * dy) / (2 * radius * radius)) * strength
                    out[yy, xx] = out[yy, xx] * (1 - falloff) + tint * falloff
    return np.clip(out, 0, 255).astype(np.uint8)


# The moon's concave (inner) edge, fitted to points on its visible horns: a circle through both tips.
INNER_CENTER = (701.99, 209.34)
INNER_RADIUS = 194.79
LEFT_TIP = (507, 232)
RIGHT_TIP = (822, 364)


def below_chord(xs, ys):
    """Below the line joining the moon's tips: where the moon's body can be."""
    (x0, y0), (x1, y1) = LEFT_TIP, RIGHT_TIP
    return ys > y0 + (xs - x0) * (y1 - y0) / (x1 - x0)


def push_pull(image, weight, levels=7):
    """Fills unknown pixels (weight 0) smoothly from known ones (weight 1)."""
    images, weights = [image * weight[..., None]], [weight]
    for _ in range(levels):
        images.append(cv2.pyrDown(images[-1]))
        weights.append(cv2.pyrDown(weights[-1]))
    estimate = images[-1] / np.maximum(weights[-1][..., None], 1e-6)
    for level in range(levels - 1, -1, -1):
        h, w = weights[level].shape
        up = cv2.pyrUp(estimate, dstsize=(w, h))
        known = images[level] / np.maximum(weights[level][..., None], 1e-6)
        confidence = np.clip(weights[level] * 3, 0, 1)[..., None]
        estimate = known * confidence + up * (1 - confidence)
    return estimate


def fill_behind_corgi(rgb, hole, rng):
    """What the corgi covers: sky inside the crescent's hollow, and a strip of the moon's surface under its
    paws and tail. The sky comes from a smooth fit of the surrounding sky; the moon from the visible surface
    at the same depth below the inner edge, mirrored in from both sides."""
    h, w = hole.shape
    out = rgb.astype(np.float32)
    ys, xs = np.mgrid[0:h, 0:w]
    cx, cy = INNER_CENTER
    depth = np.hypot(xs - cx, ys - cy) - INNER_RADIUS
    angle = np.degrees(np.arctan2(ys - cy, xs - cx))
    moon_zone = (depth > 0) & below_chord(xs, ys)
    lum = luminance(out)

    # Sky: the surrounding sky (no stars, moon or corgi) pulled inwards with a push-pull pyramid, so the fill
    # keeps the soft glow the illustration has around the corgi, without seams.
    stars = (lum - cv2.medianBlur(lum.astype(np.uint8), 9).astype(np.float32)) > 18
    sky = (hole == 0) & ~moon_zone & ~dilate(stars.astype(np.uint8) * 255, 2).astype(bool) & (lum < 140)
    out[hole > 0] = push_pull(out, sky.astype(np.float32))[hole > 0]

    # Moon surface under the corgi: for each depth band, mirror the visible surface in from both edges.
    moon_hole = (hole > 0) & moon_zone
    visible_moon = moon_zone & (hole == 0) & (angle > 55) & (angle < 170)
    if moon_hole.any():
        for d in range(0, int(depth[moon_hole].max()) + 2):
            band_hole = moon_hole & (depth >= d) & (depth < d + 1)
            if not band_hole.any():
                continue
            band_seen = visible_moon & (depth >= d - 1) & (depth < d + 2)
            a_hole = angle[band_hole]
            lo, hi = a_hole.min(), a_hole.max()
            seen_a = angle[band_seen]
            seen_c = out[band_seen] if band_seen.any() else None
            if seen_c is None or len(seen_a) < 4:
                continue
            order = np.argsort(seen_a)
            seen_a, seen_c = seen_a[order], seen_c[order]
            span = max(hi - lo, 1e-3)
            t = (a_hole - lo) / span
            # Mirror: a point δ inside the hole from its low edge samples δ outside that edge (and the same
            # for the high edge), blended across the hole.
            from_low = np.interp(lo - (a_hole - lo) % 12, seen_a, np.arange(len(seen_a)))
            from_high = np.interp(hi + (hi - a_hole) % 12, seen_a, np.arange(len(seen_a)))
            c_low = seen_c[np.clip(from_low.round().astype(int), 0, len(seen_a) - 1)]
            c_high = seen_c[np.clip(from_high.round().astype(int), 0, len(seen_a) - 1)]
            out[band_hole] = c_low * (1 - t)[:, None] + c_high * t[:, None]
    filled = np.clip(out, 0, 255).astype(np.uint8)
    sky_hole = ((hole > 0) & ~moon_zone).astype(np.uint8) * 255
    filled = add_grain(filled, sky_hole, rng, sigma=2.0)
    filled = add_stars(filled, sky_hole, rng, density=0.0005)
    filled = add_grain(filled, moon_hole.astype(np.uint8) * 255, rng, sigma=2.5)
    # Feather the fill into the untouched picture.
    soft = cv2.GaussianBlur((hole > 0).astype(np.float32), (7, 7), 0)[..., None]
    return (filled * soft + rgb.astype(np.float32) * (1 - soft)).astype(np.uint8)


def add_grain(rgb, region, rng, sigma=2.2):
    noise = rng.normal(0, sigma, rgb.shape).astype(np.float32)
    out = rgb.astype(np.float32)
    out[region > 0] += noise[region > 0]
    return np.clip(out, 0, 255).astype(np.uint8)


def upscale(image, mode):
    w, h = image.size
    big = image.resize((w * SCALE, h * SCALE), Image.LANCZOS)
    if mode == "RGB":
        big = big.filter(ImageFilter.UnsharpMask(radius=1.6, percent=55, threshold=2))
    return big


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", help="write masks and previews here")
    args = parser.parse_args()
    rng = np.random.default_rng(SEED)
    rgb = np.array(Image.open(REFERENCE).convert("RGB"))[:HERO_HEIGHT]

    words = text_masks(rgb)
    corgi = corgi_mask(rgb)
    lenses = lens_mask(rgb, corgi)

    # Scene: remove the words first (small strokes over sky, lake and trees).
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    clean = cv2.inpaint(bgr, words, 9, cv2.INPAINT_TELEA)
    clean = cv2.cvtColor(clean, cv2.COLOR_BGR2RGB)
    sky_fill = words.copy()
    clean = add_grain(clean, sky_fill, rng)
    clean = add_stars(clean, sky_fill, rng, density=0.0011)

    # Behind the corgi: the moon and sky, filled from their surroundings. Only seen for a moment while the
    # corgi settles, so a soft fill is enough.
    scene = fill_behind_corgi(clean, dilate(corgi, 6), rng)

    # Corgi layer: the original pixels (words removed) with a feathered alpha.
    alpha = cv2.GaussianBlur(corgi, (5, 5), 0)
    corgi_rgba = np.dstack([clean, alpha])
    bx, by, bw, bh = cv2.boundingRect(corgi)
    pad = 6
    crop = (max(0, bx - pad), max(0, by - pad), min(rgb.shape[1], bx + bw + pad), min(rgb.shape[0], by + bh + pad))

    os.makedirs(OUT, exist_ok=True)
    upscale(Image.fromarray(scene), "RGB").save(os.path.join(OUT, "private_hero_scene.webp"), quality=90, method=6)
    corgi_img = Image.fromarray(corgi_rgba, "RGBA").crop(crop)
    upscale(corgi_img, "RGBA").save(os.path.join(OUT, "private_hero_corgi.webp"), quality=92, method=6)
    lens_img = Image.fromarray(np.dstack([np.full_like(lenses, 255)] * 3 + [lenses]), "RGBA").crop(crop)
    upscale(lens_img, "RGBA").save(os.path.join(OUT, "private_hero_lenses.webp"), lossless=True, method=6)

    # Registration of the corgi layer inside the scene, as fractions of the scene's size (for Kotlin).
    w, h = rgb.shape[1], rgb.shape[0]
    print(f"scene {w}x{h} (x{SCALE}); corgi crop {crop}")
    print(f"CORGI_LEFT = {crop[0] / w:.5f}f, CORGI_TOP = {crop[1] / h:.5f}f, "
          f"CORGI_WIDTH = {(crop[2] - crop[0]) / w:.5f}f, CORGI_HEIGHT = {(crop[3] - crop[1]) / h:.5f}f")

    if args.debug:
        os.makedirs(args.debug, exist_ok=True)
        Image.fromarray(words).save(os.path.join(args.debug, "mask-words.png"))
        Image.fromarray(corgi).save(os.path.join(args.debug, "mask-corgi.png"))
        Image.fromarray(lenses).save(os.path.join(args.debug, "mask-lenses.png"))
        Image.fromarray(clean).save(os.path.join(args.debug, "clean-with-corgi.png"))
        Image.fromarray(scene).save(os.path.join(args.debug, "scene.png"))
        overlay = rgb.copy()
        overlay[corgi > 0] = (overlay[corgi > 0] * 0.5 + np.array([255, 0, 0]) * 0.5).astype(np.uint8)
        Image.fromarray(overlay).save(os.path.join(args.debug, "corgi-overlay.png"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
