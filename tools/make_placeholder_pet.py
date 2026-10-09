#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成占位桌宠「团子」的帧动画素材。

产出：pet-app/app/src/main/assets/pet/default/<动画>/<序号>.png + pet.json

约定（和 App 里的 PetSkin 读取格式一致）：
  - 每帧同样大小（256x256），透明背景
  - 脚底线固定在 GL，这样换成别的动画时宠物不会上下乱飘
  - 只出「朝右」的图，朝左由 App 水平翻转

注意 Pillow 的坑：在 RGBA 图上 ImageDraw 是「覆盖」而不是「混合」，
所以所有半透明的部分（高光/腮红/影子/星星/Z）都必须画在独立的
透明图层上，再用 Image.alpha_composite 合成，否则会变成黑洞。

之后你想换成自己的素材，直接跑 tools/import_assets.py 即可。
"""

import json
import os
import shutil

from PIL import Image, ImageDraw

SIZE = 256
CX = 128.0
GL = 242.0          # 脚底线：宠物站在这一行上（下面留出影子的位置）
FEET_RX, FEET_RY = 18.0, 12.0
BODY_RX, BODY_RY = 76.0, 68.0

INK = (18, 32, 43, 255)
BODY_TOP = (146, 233, 255)
BODY_BOTTOM = (44, 150, 212)
FOOT = (58, 168, 214, 255)
TUFT = (95, 211, 243, 255)

OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets", "pet", "default",
)


# --------------------------------------------------------------------- 基础绘制

def vertical_gradient(w, h, top, bottom):
    grad = Image.new("RGB", (1, h))
    px = grad.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = (
            round(top[0] + (bottom[0] - top[0]) * t),
            round(top[1] + (bottom[1] - top[1]) * t),
            round(top[2] + (bottom[2] - top[2]) * t),
        )
    return grad.resize((w, h), Image.BILINEAR)


def paste_ellipse_gradient(img, cx, cy, rx, ry, top, bottom):
    w = max(2, int(rx * 2))
    h = max(2, int(ry * 2))
    grad = vertical_gradient(w, h, top, bottom)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, h - 1), fill=255)
    img.paste(grad, (int(round(cx - rx)), int(round(cy - ry))), mask)


def new_overlay():
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def draw_eye(d, dov, ex, ey, r, style, openness):
    """深色部分画在不透明层，白色高光画在透明层。"""
    if style == "happy":
        d.arc([ex - r, ey - r, ex + r, ey + r], start=200, end=340, fill=INK, width=7)
        return
    if style == "closed" or openness <= 0.05:
        d.line([(ex - r, ey), (ex + r, ey)], fill=INK, width=7)
        return
    ry = max(2.0, r * openness)
    d.ellipse([ex - r, ey - ry, ex + r, ey + ry], fill=INK)
    hr = max(2.0, r * 0.34)
    hx = ex - r * 0.34
    hy = ey - ry * 0.42
    dov.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=(255, 255, 255, 235))


def draw_mouth(d, mx, my, kind):
    if kind == "big":
        d.chord([mx - 19, my - 13, mx + 19, my + 19], start=0, end=180, fill=INK)
    elif kind == "open":
        d.ellipse([mx - 9, my - 8, mx + 9, my + 11], fill=INK)
    elif kind == "flat":
        d.line([(mx - 11, my), (mx + 11, my)], fill=INK, width=5)
    else:  # smile
        d.arc([mx - 15, my - 11, mx + 15, my + 12], start=20, end=160, fill=INK, width=5)


def draw_sparkle(d, sx, sy, r, color):
    d.line([(sx - r, sy), (sx + r, sy)], fill=color, width=3)
    d.line([(sx, sy - r), (sx, sy + r)], fill=color, width=3)
    d.line([(sx - r * 0.55, sy - r * 0.55), (sx + r * 0.55, sy + r * 0.55)], fill=color, width=2)
    d.line([(sx - r * 0.55, sy + r * 0.55), (sx + r * 0.55, sy - r * 0.55)], fill=color, width=2)


def draw_z(d, x, y, s, w, color):
    d.line([(x, y), (x + s, y)], fill=color, width=w)
    d.line([(x + s, y), (x, y + s)], fill=color, width=w)
    d.line([(x, y + s), (x + s, y + s)], fill=color, width=w)


# --------------------------------------------------------------------- 单帧渲染

def render(p):
    squash = p.get("squash", 1.0)
    stretch = p.get("stretch", 1.0)
    bob = p.get("bob", 0.0)
    anchor = p.get("anchor", "bottom")
    eye_style = p.get("eye_style", "normal")
    eye_open = p.get("eye_open", 1.0)
    mouth = p.get("mouth", "smile")
    blush = p.get("blush", 1.0)
    feet = p.get("feet", 0)
    zzz = p.get("zzz", 0)
    sparkle = p.get("sparkle", 0)
    lean = p.get("lean", 0.0)

    rx = BODY_RX * stretch
    ry = BODY_RY * squash

    if anchor == "top":
        top = p.get("top", 60.0) + bob
        cy = top + ry
    else:
        cy = (GL - 18.0) + bob - ry
    body_bottom = cy + ry
    body_top = cy - ry

    # 1) 影子单独一层，压在最下面
    img = new_overlay()
    ov = new_overlay()
    dov = ImageDraw.Draw(ov)
    lift = max(0.0, -bob)
    sw = max(22.0, 58.0 - lift * 0.55)
    dov.ellipse([CX - sw, GL - 7, CX + sw, GL + 9], fill=(0, 0, 0, 80))
    img = Image.alpha_composite(img, ov)

    # 2) 不透明部分：脚、呆毛、身体
    d = ImageDraw.Draw(img)
    feet_cy = body_bottom + 6.0
    for sign in (-1, 1):
        dy = 0.0
        if feet:
            dy = -6.0 if (sign * feet) > 0 else 4.0
        fx = CX + sign * 31.0
        d.ellipse([fx - FEET_RX, feet_cy + dy - FEET_RY,
                   fx + FEET_RX, feet_cy + dy + FEET_RY], fill=FOOT)

    d.polygon([(CX - 6, body_top + 6), (CX + 7, body_top + 5), (CX + 1, body_top - 20)],
              fill=TUFT)

    paste_ellipse_gradient(img, CX, cy, rx, ry, BODY_TOP, BODY_BOTTOM)

    # 3) 半透明部分统一放在一层里混合
    ov2 = new_overlay()
    dov2 = ImageDraw.Draw(ov2)

    dov2.ellipse([CX - rx * 0.58, cy - ry * 0.70, CX - rx * 0.08, cy - ry * 0.26],
                 fill=(255, 255, 255, 78))

    if blush > 0:
        a = int(120 * blush)
        dov2.ellipse([CX - 60, cy + 4, CX - 30, cy + 22], fill=(255, 138, 162, a))
        dov2.ellipse([CX + 30, cy + 4, CX + 60, cy + 22], fill=(255, 138, 162, a))

    if sparkle >= 1:
        draw_sparkle(dov2, CX - 88, cy - 34, 11, (255, 244, 160, 240))
    if sparkle >= 2:
        draw_sparkle(dov2, CX + 90, cy - 46, 13, (255, 244, 160, 240))
        draw_sparkle(dov2, CX + 74, cy - 78, 8, (255, 255, 255, 225))

    for i in range(zzz):
        sz = 15 + i * 7
        draw_z(dov2, CX + 66 + i * 24, cy - 52 - i * 32, sz, 5,
               (232, 244, 252, max(60, 235 - i * 55)))

    # 4) 眼睛和嘴画在身体之上
    d2 = ImageDraw.Draw(img)
    eye_r = 12.0 if eye_style != "wide" else 14.0
    draw_eye(d2, dov2, CX - 25, cy - 6, eye_r, eye_style, eye_open)
    draw_eye(d2, dov2, CX + 25, cy - 6, eye_r, eye_style, eye_open)
    draw_mouth(d2, CX, cy + 22, mouth)

    img = Image.alpha_composite(img, ov2)

    if lean:
        img = img.rotate(lean, resample=Image.BICUBIC, center=(CX, body_bottom - 20))

    return img


# --------------------------------------------------------------------- 动画表

def s(squash, **kw):
    """默认横向反向补偿，让呼吸看起来是体积守恒的。"""
    kw.setdefault("stretch", 1.0 - (squash - 1.0) * 0.6)
    kw["squash"] = squash
    return kw


ANIMATIONS = {
    "idle": {
        "fps": 8, "loop": True,
        "frames": [
            s(1.00, bob=0.0),
            s(1.03, bob=-1.5),
            s(1.06, bob=-3.0),
            s(1.06, bob=-3.0),
            s(1.03, bob=-1.5),
            s(1.00, bob=0.0),
        ],
    },
    "blink": {
        "fps": 12, "loop": False,
        "frames": [
            dict(eye_open=1.0),
            dict(eye_open=0.35),
            dict(eye_open=0.0, eye_style="closed"),
            dict(eye_open=0.5),
        ],
    },
    "happy": {
        "fps": 12, "loop": False,
        "frames": [
            s(0.90, bob=0.0, eye_style="happy", mouth="big", sparkle=0),
            s(1.10, bob=-14.0, eye_style="happy", mouth="big", sparkle=0),
            s(1.04, bob=-30.0, eye_style="happy", mouth="big", sparkle=1),
            s(1.00, bob=-38.0, eye_style="happy", mouth="big", sparkle=2),
            s(1.00, bob=-36.0, eye_style="happy", mouth="big", sparkle=2),
            s(1.05, bob=-24.0, eye_style="happy", mouth="big", sparkle=1),
            s(1.06, bob=-8.0, eye_style="happy", mouth="big", sparkle=0),
            s(0.88, bob=0.0, eye_style="happy", mouth="smile", sparkle=0),
        ],
    },
    "drag": {
        "fps": 8, "loop": True,
        "frames": [
            dict(squash=1.14, stretch=0.90, anchor="top", top=56.0,
                 eye_style="wide", mouth="open", lean=-3.0),
            dict(squash=1.16, stretch=0.89, anchor="top", top=58.0,
                 eye_style="wide", mouth="open", lean=0.0),
            dict(squash=1.14, stretch=0.90, anchor="top", top=56.0,
                 eye_style="wide", mouth="open", lean=3.0),
            dict(squash=1.15, stretch=0.90, anchor="top", top=57.0,
                 eye_style="wide", mouth="open", lean=0.0),
        ],
    },
    "fall": {
        "fps": 10, "loop": True,
        "frames": [
            dict(squash=1.20, stretch=0.88, anchor="top", top=50.0,
                 eye_style="wide", mouth="open", lean=-4.0),
            dict(squash=1.24, stretch=0.87, anchor="top", top=48.0,
                 eye_style="wide", mouth="open", lean=0.0),
            dict(squash=1.20, stretch=0.88, anchor="top", top=50.0,
                 eye_style="wide", mouth="open", lean=4.0),
        ],
    },
    "walk": {
        "fps": 10, "loop": True,
        "frames": [
            s(1.00, bob=0.0, feet=0, lean=-2.0),
            s(1.01, bob=-3.0, feet=1, lean=0.0),
            s(1.02, bob=-5.0, feet=0, lean=1.0),
            s(1.01, bob=-3.0, feet=-1, lean=2.0),
            s(1.00, bob=0.0, feet=0, lean=1.0),
            s(1.01, bob=-2.0, feet=1, lean=0.0),
        ],
    },
    "sleep": {
        "fps": 5, "loop": True,
        "frames": [
            s(1.00, bob=0.0, eye_style="closed", mouth="flat", blush=0.7, zzz=0),
            s(1.04, bob=-2.0, eye_style="closed", mouth="flat", blush=0.7, zzz=1),
            s(1.07, bob=-3.0, eye_style="closed", mouth="flat", blush=0.7, zzz=2),
            s(1.07, bob=-3.0, eye_style="closed", mouth="flat", blush=0.7, zzz=2),
            s(1.04, bob=-2.0, eye_style="closed", mouth="flat", blush=0.7, zzz=1),
            s(1.00, bob=0.0, eye_style="closed", mouth="flat", blush=0.7, zzz=0),
        ],
    },
}


def main():
    out = os.path.abspath(OUT_DIR)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out, exist_ok=True)

    manifest = {
        "name": "团子",
        "frameWidth": SIZE,
        "frameHeight": SIZE,
        "animations": {},
    }

    total = 0
    tops = []
    bottoms = []
    for name, spec in ANIMATIONS.items():
        d = os.path.join(out, name)
        os.makedirs(d, exist_ok=True)
        rels = []
        for i, params in enumerate(spec["frames"]):
            img = render(params)
            rel = "%s/%02d.png" % (name, i)
            img.save(os.path.join(out, rel.replace("/", os.sep)))
            rels.append(rel)
            total += 1
            bbox = img.getbbox()
            if bbox:
                tops.append(bbox[1])
                bottoms.append(bbox[3])
        manifest["animations"][name] = {
            "fps": spec["fps"],
            "loop": spec["loop"],
            "frames": rels,
        }

    with open(os.path.join(out, "pet.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print("frames:", total)
    print("out:", out)
    print("vertical extent: top=%d bottom=%d (canvas %d)" % (min(tops), max(bottoms), SIZE))


if __name__ == "__main__":
    main()
