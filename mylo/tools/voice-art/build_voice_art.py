#!/usr/bin/env python3
"""Builds the Voice Mode artwork from the approved reference (design/reference/voice-mode-reference.png).

Like tools/private-art, it removes the words baked into the illustration (Mylo draws them natively) and
separates the corgi, so the corgi can sit in front of the microphone card (its paws rest on the card's edge)
and its headset can glow with the conversation:

  voice_hero_scene.webp    the night scene without words, status bar, controls, speech bubble or corgi
  voice_hero_corgi.webp    the headphone corgi on transparency, registered to the scene
  voice_hero_headset.webp  an alpha mask of the headset's glowing parts, for the light pulse

All three share the reference's top 490 px (941 px wide), upscaled ×SCALE.

  python3 tools/voice-art/build_voice_art.py [--debug OUT_DIR]
"""
import argparse
import importlib.util
import os
import sys

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
REFERENCE = os.path.join(ROOT, "design", "reference", "voice-mode-reference.png")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
HERO_HEIGHT = 490  # the microphone card's top edge is at ~493
SEED = 11

_spec = importlib.util.spec_from_file_location("private_art", os.path.join(ROOT, "tools", "private-art", "build_private_art.py"))
art = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(art)
luminance, box_mask, dilate = art.luminance, art.box_mask, art.dilate


def word_masks(rgb):
    lum = luminance(rgb.astype(np.float32))
    r, g, b = (rgb[..., i].astype(np.int32) for i in range(3))
    shape = rgb.shape
    masks = []
    for box in [(30, 6, 112, 52), (700, 6, 918, 52)]:  # status bar
        masks.append(dilate(((lum > 70) & (box_mask(shape, box) > 0)).astype(np.uint8) * 255, 5))
    for box, threshold, grow in [((25, 45, 310, 168), 80, 7), ((25, 160, 340, 202), 80, 4)]:  # wordmark, tagline + paw
        masks.append(dilate(((lum > threshold) & (box_mask(shape, box) > 0)).astype(np.uint8) * 255, grow))
    # Voice Mode selector and the gear: rounded controls, removed whole.
    pill = np.zeros(shape[:2], np.uint8)
    cv2.rectangle(pill, (562, 64), (782, 150), 255, -1)
    cv2.circle(pill, (566, 107), 44, 255, -1)
    cv2.circle(pill, (778, 107), 44, 255, -1)
    cv2.circle(pill, (871, 107), 44, 255, -1)
    masks.append(pill)
    # Handwriting (lavender strokes) on both sides, with their swooshes and the heart.
    lavender = (lum > 95) & (b > g + 8) & (b >= r - 12)
    for box in [(25, 238, 232, 402), (748, 162, 930, 332)]:
        masks.append(dilate((lavender & (box_mask(shape, box) > 0)).astype(np.uint8) * 255, 3))
    # The speech bubble and its tail.
    bubble = np.zeros(shape[:2], np.uint8)
    cv2.rectangle(bubble, (668, 344), (874, 456), 255, -1)
    cv2.circle(bubble, (690, 400), 57, 255, -1)
    cv2.circle(bubble, (852, 400), 57, 255, -1)
    cv2.fillPoly(bubble, [np.array([(628, 466), (650, 420), (690, 430)], np.int32)], 255)
    masks.append(dilate(bubble, 4))
    return np.max(np.stack(masks), axis=0)


def corgi_mask(rgb):
    h, w = rgb.shape[:2]
    mask = np.full((h, w), cv2.GC_BGD, np.uint8)
    cv2.rectangle(mask, (250, 125), (722, 500), cv2.GC_PR_FGD, -1)
    for poly in [
        [(390, 250), (520, 230), (640, 300), (630, 380), (520, 420), (380, 410), (330, 330)],  # face
        [(360, 410), (600, 410), (600, 470), (360, 470)],  # hoodie
        [(270, 450), (320, 430), (335, 480), (280, 490)],  # tail
        [(320, 270), (360, 270), (360, 360), (320, 360)],  # left earcup
        [(600, 300), (640, 300), (640, 390), (600, 390)],  # right earcup
    ]:
        cv2.fillPoly(mask, [np.array(poly, np.int32)], cv2.GC_FGD)
    # Open sky and the microphone ring's glow between the paws are background.
    cv2.rectangle(mask, (250, 110), (345, 200), cv2.GC_BGD, -1)
    cv2.rectangle(mask, (660, 110), (722, 240), cv2.GC_BGD, -1)
    cv2.fillPoly(mask, [np.array([(415, 486), (520, 486), (540, 500), (400, 500)], np.int32)], cv2.GC_BGD)
    bgd = np.zeros((1, 65), np.float64)
    fgd = np.zeros((1, 65), np.float64)
    cv2.grabCut(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), mask, None, bgd, fgd, 8, cv2.GC_INIT_WITH_MASK)
    fg = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(fg)
    if count > 1:
        fg = np.where(labels == 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA])), 255, 0).astype(np.uint8)
    contours, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    solid = np.zeros_like(fg)
    cv2.drawContours(solid, contours, -1, 255, -1)
    # The headset's thin cables (over the head to the right, and down from the right earcup) belong to it.
    lum = luminance(rgb.astype(np.float32))
    line = (lum - cv2.medianBlur(lum.astype(np.uint8), 15).astype(np.float32)) > 7
    cable_zone = (box_mask(rgb.shape, (440, 175, 665, 250)) | box_mask(rgb.shape, (590, 380, 650, 480))) > 0
    cable = (line & cable_zone).astype(np.uint8) * 255
    cable = cv2.morphologyEx(cable, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(cable)
    for i in range(1, count):
        if stats[i, cv2.CC_STAT_AREA] >= 25:
            solid[labels == i] = 255
    return solid


def headset_mask(rgb, corgi):
    """The headset's glowing blue/cyan parts (earcup rims and the paw prints)."""
    f = rgb.astype(np.int32)
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    glow = (b > 170) & (g > 110) & (r < 150) & (b > r + 60)
    region = (box_mask(rgb.shape, (290, 250, 420, 470)) | box_mask(rgb.shape, (560, 280, 680, 470))) > 0
    m = (glow & region & (corgi > 0)).astype(np.uint8) * 255
    return cv2.GaussianBlur(dilate(m, 2), (7, 7), 0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug")
    args = parser.parse_args()
    rng = np.random.default_rng(SEED)
    rgb = np.array(Image.open(REFERENCE).convert("RGB"))[:HERO_HEIGHT]
    words = word_masks(rgb)
    corgi = corgi_mask(rgb)
    headset = headset_mask(rgb, corgi)
    clean = cv2.cvtColor(cv2.inpaint(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), words, 9, cv2.INPAINT_TELEA), cv2.COLOR_BGR2RGB)
    clean = art.add_grain(clean, words, rng)
    clean = art.add_stars(clean, words, rng, density=0.0009)
    # Behind the corgi there is only sky and treeline: fill it from the surroundings.
    hole = dilate(corgi, 6)
    lum = luminance(clean.astype(np.float32))
    stars = (lum - cv2.medianBlur(lum.astype(np.uint8), 9).astype(np.float32)) > 18
    known = ((hole == 0) & ~dilate(stars.astype(np.uint8) * 255, 2).astype(bool)).astype(np.float32)
    filled = clean.astype(np.float32)
    filled[hole > 0] = art.push_pull(filled, known)[hole > 0]
    scene = np.clip(filled, 0, 255).astype(np.uint8)
    scene = art.add_grain(scene, hole, rng, sigma=2.0)
    scene = art.add_stars(scene, hole, rng, density=0.0004)

    alpha = cv2.GaussianBlur(corgi, (3, 3), 0)
    bx, by, bw, bh = cv2.boundingRect(corgi)
    pad = 6
    crop = (max(0, bx - pad), max(0, by - pad), min(rgb.shape[1], bx + bw + pad), min(rgb.shape[0], by + bh + pad))
    os.makedirs(OUT, exist_ok=True)
    art.upscale(Image.fromarray(scene), "RGB").save(os.path.join(OUT, "voice_hero_scene.webp"), quality=90, method=6)
    art.upscale(Image.fromarray(np.dstack([clean, alpha]), "RGBA").crop(crop), "RGBA").save(os.path.join(OUT, "voice_hero_corgi.webp"), quality=92, method=6)
    art.upscale(Image.fromarray(np.dstack([np.full_like(headset, 255)] * 3 + [headset]), "RGBA").crop(crop), "RGBA").save(
        os.path.join(OUT, "voice_hero_headset.webp"), lossless=True, method=6)
    print(f"scene {rgb.shape[1]}x{rgb.shape[0]} (x{art.SCALE}); corgi crop {crop} (x, y, right, bottom in reference px)")
    if args.debug:
        os.makedirs(args.debug, exist_ok=True)
        Image.fromarray(words).save(os.path.join(args.debug, "voice-mask-words.png"))
        overlay = rgb.copy()
        overlay[corgi > 0] = (overlay[corgi > 0] * .5 + np.array([255, 0, 0]) * .5).astype(np.uint8)
        overlay[headset > 100] = (overlay[headset > 100] * .3 + np.array([0, 255, 0]) * .7).astype(np.uint8)
        Image.fromarray(overlay).save(os.path.join(args.debug, "voice-corgi-overlay.png"))
        Image.fromarray(scene).save(os.path.join(args.debug, "voice-scene.png"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
