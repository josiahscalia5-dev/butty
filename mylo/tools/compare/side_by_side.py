#!/usr/bin/env python3
"""Side-by-side and difference images of a Mylo capture against an approved target.

The capture is scaled to the target's width (its aspect is kept; the taller one is padded), then three panels
are written: target | capture | per-pixel difference (brighter = further apart), each labelled.

  python3 tools/compare/side_by_side.py TARGET CAPTURE OUT.jpg [--crop-target x0,y0,x1,y1] [--labels A,B]
"""
import argparse

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def main():
    p = argparse.ArgumentParser()
    p.add_argument("target")
    p.add_argument("capture")
    p.add_argument("out")
    p.add_argument("--crop-target", help="x0,y0,x1,y1 of the phone screen inside the target image")
    p.add_argument("--labels", default="APPROVED TARGET,ANDROID CAPTURE")
    p.add_argument("--no-diff", action="store_true")
    a = p.parse_args()
    target = Image.open(a.target).convert("RGB")
    if a.crop_target:
        target = target.crop(tuple(int(v) for v in a.crop_target.split(",")))
    cap = Image.open(a.capture).convert("RGB")
    cap = cap.resize((target.width, round(cap.height * target.width / cap.width)), Image.LANCZOS)
    h = max(target.height, cap.height)
    panels = [target, cap]
    labels = a.labels.split(",")
    if not a.no_diff:
        t = np.zeros((h, target.width, 3), np.float32); c = np.zeros_like(t)
        t[:target.height] = np.asarray(target, np.float32); c[:cap.height] = np.asarray(cap, np.float32)
        d = np.abs(t - c).mean(axis=2)
        diff = Image.fromarray(np.clip(d * 3, 0, 255).astype(np.uint8)).convert("RGB")
        panels.append(diff)
        labels.append(f"DIFFERENCE (mean {d.mean():.1f}/255)")
    gap, head = 24, 56
    out = Image.new("RGB", (len(panels) * target.width + (len(panels) + 1) * gap, h + head + gap), (12, 14, 24))
    draw = ImageDraw.Draw(out)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
    except OSError:
        font = ImageFont.load_default()
    for i, (panel, label) in enumerate(zip(panels, labels)):
        x = gap + i * (target.width + gap)
        out.paste(panel, (x, head))
        draw.text((x, 16), label, fill=(235, 235, 245), font=font)
    out.save(a.out, quality=90)


if __name__ == "__main__":
    main()
