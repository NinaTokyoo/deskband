"""大小取舍预览：只变大小，朝向固定为「头朝左」

已知：
  - 两套素材是同一张画的两个分辨率版本（corner 1440x1352, border 1024x875）
  - 头宽 1406 vs 917  ->  相差 1.533 倍
  - 两者都紧贴裁剪，无留白

问题：按"高度 = 屏幕15%"渲染，边框那套人物会小 1.53 倍，切换状态时人物会突然变小。
本图把两种做法并排画出来，让用户选。
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
OUT = Path(r"D:\dsh\dsh work\pet-app\docs\size-compare.png")

NAME = "后藤一里"
SW, SH = 1440, 3168
FRAC = 0.15
VIEW = 0.30
VW, VH = int(SW * VIEW), int(SH * VIEW)
COMP = 1406 / 917


def head_width(im, top_frac=0.40):
    bbox = im.getbbox()
    if not bbox:
        return 0
    x0, y0, x1, y1 = bbox
    h = y1 - y0
    px = im.load()
    best = 0
    for y in range(y0, y0 + int(h * top_frac), 3):
        row = [x for x in range(x0, x1) if px[x, y][3] > 16]
        if row:
            best = max(best, row[-1] - row[0] + 1)
    return best


def scale_to_h(im, target_h):
    r = target_h / im.height
    return im.resize((max(1, round(im.width * r)), max(1, round(target_h))), Image.LANCZOS)


def mini(im):
    return im.resize((max(1, round(im.width * VIEW)), max(1, round(im.height * VIEW))), Image.LANCZOS)


def caption(im, text, sub=""):
    im = im.copy()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 58 if sub else 38], fill=(15, 15, 18, 248))
    d.text((8, 8), text, fill=(255, 255, 255, 255))
    if sub:
        d.text((8, 28), sub, fill=(150, 205, 255, 255))
    return im


def ruler(im, x, y0, y1, label, color=(255, 210, 60, 255)):
    d = ImageDraw.Draw(im)
    d.line([(x, y0), (x, y1)], fill=color, width=2)
    for yy in (y0, y1):
        d.line([(x - 6, yy), (x + 6, yy)], fill=color, width=2)
    d.text((x + 9, (y0 + y1) // 2 - 6), label, fill=color)
    return im


def panel_corner(corner):
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    c = mini(scale_to_h(corner, SH * FRAC))
    px, py = VW - c.width, VH - c.height
    s.alpha_composite(c, (px, py))
    s = ruler(s, max(6, px - 14), py, py + c.height, "15%")
    return caption(s, "1. CORNER as-is", "height = 15% of screen")


def panel_border(border, frac, text, sub):
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    b = mini(scale_to_h(border.rotate(90, expand=True), SH * frac))
    px, py = VW - b.width, (VH - b.height) // 2
    s.alpha_composite(b, (px, py))
    s = ruler(s, max(6, px - 14), py, py + b.height, f"{frac/SH*100:.0f}%h")
    return caption(s, text, sub)


def main():
    corner = Image.open(ROOT / "右下角" / f"{NAME}.png").convert("RGBA")
    border = Image.open(ROOT / "右边框" / f"{NAME}.png").convert("RGBA")
    print(f"corner={corner.size} border={border.size} comp={COMP:.3f}")
    print(f"原始高度比: {border.height/corner.height:.4f}  -> "
          f"要让两套一样大，border 需放大 {COMP:.3f}x")

    panels = [
        panel_corner(corner),
        panel_border(border, FRAC, "2. BORDER  raw (no compensation)",
                     f"height={FRAC*100:.0f}%  ->  pet 1.53x smaller"),
        panel_border(border, FRAC * COMP, "3. BORDER  x1.53 compensated",
                     "same pet size, but wider footprint"),
    ]

    pad = 12
    canvas = Image.new("RGBA", ((VW + pad) * len(panels) + pad, VH + pad * 2), (18, 19, 22, 255))
    for i, p in enumerate(panels):
        canvas.alpha_composite(p, (pad + i * (VW + pad), pad))
    canvas.save(OUT)
    print("saved:", OUT, canvas.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
