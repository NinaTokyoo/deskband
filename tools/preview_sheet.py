#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把所有动画帧拼成一张预览图，方便肉眼检查动作是否正常。"""

import os
from PIL import Image

ASSETS = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets", "pet", "default",
)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "preview.png")

ORDER = ["idle", "blink", "happy", "drag", "fall", "walk", "sleep"]
SCALE = 0.55
BG = (14, 17, 22, 255)


def main():
    assets = os.path.abspath(ASSETS)
    rows = []
    for name in ORDER:
        d = os.path.join(assets, name)
        if not os.path.isdir(d):
            continue
        files = sorted(f for f in os.listdir(d) if f.endswith(".png"))
        imgs = [Image.open(os.path.join(d, f)).convert("RGBA") for f in files]
        rows.append((name, imgs))

    cell = int(256 * SCALE)
    cols = max(len(r[1]) for r in rows)
    sheet = Image.new("RGBA", (cols * cell, len(rows) * cell), BG)

    for r, (name, imgs) in enumerate(rows):
        for c, im in enumerate(imgs):
            small = im.resize((cell, cell), Image.LANCZOS)
            sheet.alpha_composite(small, (c * cell, r * cell))

    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    sheet.convert("RGB").save(os.path.abspath(OUT))
    print("rows:", [r[0] for r in rows])
    print("preview:", os.path.abspath(OUT), sheet.size)


if __name__ == "__main__":
    main()
