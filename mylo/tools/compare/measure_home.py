#!/usr/bin/env python3
"""Measures Home elements in the approved target and in a capture (scaled to 864 px wide) and prints the offsets.

Each probe finds the bounding box of pixels matching a colour test inside a search window (target px), so the
same probe works on both images. Offsets are capture minus target, in target px (2.1 px = 1 dp).

  python3 tools/compare/measure_home.py CAPTURE [--target design/reference/home-reference.jpg]
"""
import argparse
import os

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

def lum(a): return a.mean(axis=2)
def sat(a): return a.max(axis=2) - a.min(axis=2)

PROBES = [
    # name, window (x0, y0, x1, y1), test
    ("greeting disc", (30, 50, 140, 140), lambda a: (a[..., 2] > 95) & (a[..., 0] < 130) & (lum(a) > 55)),
    ("moon glyph", (55, 70, 115, 125), lambda a: a[..., 0] > 170),
    ("Good evening!", (135, 66, 330, 98), lambda a: lum(a) > 150),
    ("Have a brighter browse", (135, 99, 340, 126), lambda a: lum(a) > 120),
    ("sparkles", (330, 95, 400, 130), lambda a: (a[..., 0] > 170) & (a[..., 2] < 140)),
    ("gear glyph", (760, 70, 815, 125), lambda a: lum(a) > 200),
    ("wordmark", (80, 150, 415, 298), lambda a: lum(a) > 170),
    ("tagline", (95, 296, 400, 334), lambda a: lum(a) > 150),
    ("search pill", (20, 430, 850, 570), lambda a: lum(a) > 200),
    ("magnifier", (60, 470, 125, 530), lambda a: lum(a) < 120),
    ("placeholder", (140, 480, 470, 520), lambda a: lum(a) < 170),
    ("mic", (645, 470, 700, 530), lambda a: lum(a) < 120),
    ("scanner", (740, 470, 810, 530), lambda a: lum(a) < 120),
    ("Explore disk", (50, 590, 200, 700), lambda a: (a[..., 2] > 150) & (sat(a) > 90)),
    ("AI disk", (660, 590, 810, 700), lambda a: (a[..., 0] > 180) & (sat(a) > 90)),
    ("Explore label", (80, 712, 180, 750), lambda a: lum(a) > 150),
    ("Private tile", (40, 770, 180, 905), lambda a: sat(a) > 80),
    ("Private title", (180, 790, 330, 820), lambda a: lum(a) > 150),
    ("Private subtitle", (180, 826, 360, 882), lambda a: lum(a) > 120),
    ("chevron 1", (370, 815, 415, 855), lambda a: lum(a) > 120),
    ("Tools tile", (446, 922, 600, 1058), lambda a: sat(a) > 80),
    ("Tools subtitle", (590, 980, 790, 1036), lambda a: lum(a) > 120),
    ("shield badge", (40, 1080, 160, 1185), lambda a: (a[..., 1] > a[..., 2] + 20) & (a[..., 1] > 90)),
    ("Mylo Shield", (160, 1100, 320, 1136), lambda a: lum(a) > 150),
    ("Not connected", (160, 1137, 320, 1162), lambda a: lum(a) > 110),
    ("Set up text", (650, 1115, 790, 1150), lambda a: lum(a) > 170),
    ("banner title", (60, 1222, 330, 1300), lambda a: lum(a) > 215),
    ("Explore today", (70, 1315, 236, 1350), lambda a: lum(a) > 170),
    ("banner button", (236, 1300, 300, 1362), lambda a: lum(a) > 180),
    ("nav pill", (55, 1395, 205, 1465), lambda a: (a[..., 2] > 200) & (a[..., 0] > 170)),
    ("Home label", (80, 1460, 180, 1492), lambda a: lum(a) > 150),
    ("Search icon", (290, 1400, 370, 1458), lambda a: lum(a) > 150),
    ("Tabs box", (490, 1400, 580, 1458), lambda a: lum(a) > 150),
    ("Mylo icon", (690, 1400, 780, 1458), lambda a: lum(a) > 150),
]


def bbox(img, window, test):
    x0, y0, x1, y1 = window
    m = test(img[y0:y1, x0:x1].astype(np.int32))
    ys, xs = np.nonzero(m)
    if len(xs) < 3:
        return None
    return (xs.min() + x0, ys.min() + y0, xs.max() + x0, ys.max() + y0)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("capture")
    p.add_argument("--target", default=os.path.join(ROOT, "design", "reference", "home-reference.jpg"))
    a = p.parse_args()
    t = np.asarray(Image.open(a.target).convert("RGB"))
    c = Image.open(a.capture).convert("RGB")
    c = np.asarray(c.resize((864, round(c.height * 864 / c.width)), Image.LANCZOS))
    print(f"{'element':24} {'target box':>22} {'capture box':>22}   dx0  dy0  dx1  dy1  (px; 2.1 px = 1 dp)")
    for name, window, test in PROBES:
        tb, cb = bbox(t, window, test), bbox(c, window, test)
        if tb is None or cb is None:
            print(f"{name:24} {str(tb):>22} {str(cb):>22}   (missing)")
            continue
        d = [cb[i] - tb[i] for i in range(4)]
        flag = "  <--" if max(abs(v) for v in d) > 4 else ""
        print(f"{name:24} {str(tuple(int(v) for v in tb)):>22} {str(tuple(int(v) for v in cb)):>22}  {d[0]:4d} {d[1]:4d} {d[2]:4d} {d[3]:4d}{flag}")


if __name__ == "__main__":
    main()
