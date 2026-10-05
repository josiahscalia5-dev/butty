#!/usr/bin/env python3
"""Traces the Mylo wordmark ("My" in white, "lo" and its smile in lavender) from the approved Home target into
vector paths for HomeArt.kt, so the logo keeps the target's exact letterforms and stays sharp at every density.

Prints Kotlin constants (path data in a 0..W × 0..H viewport, target px × 10 / OVERSAMPLE scale).
Requires numpy, Pillow, opencv-python-headless and potracer.

  python3 tools/home-art/trace_wordmark.py > /tmp/wordmark.kt
"""
import os

import cv2
import numpy as np
import potrace
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REFERENCE = os.path.join(ROOT, "design", "reference", "home-reference.jpg")
BOX = (80, 150, 420, 300)  # target px around the wordmark (the tagline starts at y 298)
OVERSAMPLE = 8


def main():
    rgb = np.asarray(Image.open(REFERENCE).convert("RGB")).astype(np.float32)
    x0, y0, x1, y1 = BOX
    sub = rgb[y0:y1, x0:x1]
    lum = sub[..., 0] * .299 + sub[..., 1] * .587 + sub[..., 2] * .114
    # Coverage: 0 on the sky (~45), 1 on the letters (~225), linear across the anti-aliased edge.
    cover = np.clip((lum - 70) / (200 - 70), 0, 1)
    cover[148:, :] = 0  # tagline
    # Keep only the large shapes (letters and smile), not stars.
    n, lab, stats, _ = cv2.connectedComponentsWithStats((cover > .5).astype(np.uint8))
    keep = np.zeros(cover.shape, bool)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] > 150:
            keep |= lab == i
    keep = cv2.dilate(keep.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
    cover *= keep
    # Lavender ("lo" and the smile): blue clearly above red.
    lavender = (sub[..., 2] - sub[..., 0]) > 18
    big = cv2.resize(cover, None, fx=OVERSAMPLE, fy=OVERSAMPLE, interpolation=cv2.INTER_CUBIC)
    big = cv2.GaussianBlur(big, (0, 0), OVERSAMPLE * .35)
    shape = big > .5
    # Each letter (and the smile) is one shape; a shape is lavender when most of it is blue clearly above red.
    lav_big = cv2.resize(lavender.astype(np.float32), None, fx=OVERSAMPLE, fy=OVERSAMPLE, interpolation=cv2.INTER_LINEAR)
    count, labels = cv2.connectedComponents(shape.astype(np.uint8))
    my, lo = np.zeros_like(shape), np.zeros_like(shape)
    for i in range(1, count):
        part = labels == i
        (lo if lav_big[part].mean() > .5 else my)[part] = True
    ys, xs = np.nonzero(shape)
    ox, oy = xs.min(), ys.min()
    w, h = xs.max() - ox + 1, ys.max() - oy + 1
    scale = 10 / OVERSAMPLE  # path units: target px × 10
    print(f"// Traced from design/reference/home-reference.jpg; bounds in target px: x {x0 + ox / OVERSAMPLE:.1f}, "
          f"y {y0 + oy / OVERSAMPLE:.1f}, {w / OVERSAMPLE:.1f} × {h / OVERSAMPLE:.1f}")
    print(f"const val WORDMARK_WIDTH = {w * scale:.1f}f")
    print(f"const val WORDMARK_HEIGHT = {h * scale:.1f}f")
    for name, mask in [("WORDMARK_MY", my), ("WORDMARK_LO", lo)]:
        bmp = potrace.Bitmap(~mask)  # potracer traces the dark (False) pixels
        path = bmp.trace(turdsize=20, alphamax=1.0, opticurve=True, opttolerance=0.2)
        out = []
        for curve in path:
            sx, sy = curve.start_point.x, curve.start_point.y
            out.append(f"M{(sx - ox) * scale:.1f},{(sy - oy) * scale:.1f}")
            for seg in curve.segments:
                if seg.is_corner:
                    c, e = (seg.c.x, seg.c.y), (seg.end_point.x, seg.end_point.y)
                    out.append(f"L{(c[0] - ox) * scale:.1f},{(c[1] - oy) * scale:.1f}L{(e[0] - ox) * scale:.1f},{(e[1] - oy) * scale:.1f}")
                else:
                    a, b, e = (seg.c1.x, seg.c1.y), (seg.c2.x, seg.c2.y), (seg.end_point.x, seg.end_point.y)
                    out.append(f"C{(a[0] - ox) * scale:.1f},{(a[1] - oy) * scale:.1f} {(b[0] - ox) * scale:.1f},{(b[1] - oy) * scale:.1f} "
                               f"{(e[0] - ox) * scale:.1f},{(e[1] - oy) * scale:.1f}")
            out.append("Z")
        data = "".join(out)
        chunks = [data[i:i + 140] for i in range(0, len(data), 140)]
        print(f"private const val {name} =")
        print(" +\n".join(f'    "{c}"' for c in chunks))


if __name__ == "__main__":
    main()
