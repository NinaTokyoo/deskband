"""最终定稿预览：两种旋转方向 + 大小补偿

已确认的事实：
  - 两套素材是**同一张画**的两个分辨率版本（diff 38.7 直接缩放最像；旋转后差异 65~80）
    => 两个文件本身都是"人物正立"，旋转是**渲染时**做的，不是文件本身躺着
  - 两者都是紧贴裁剪（无留白），所以"贴边"由文件边缘天然保证
  - 大小差 1.53 倍（头宽 1406 vs 917），按"高度15%"渲染时边框那套会明显偏小

本图把两种旋转都画到真机尺寸(1440x3168)，并带上 1.53x 大小补偿，用来定方向。
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
OUT = Path(r"D:\dsh\dsh work\pet-app\docs\final-orientation.png")

NAME = "后藤一里"
SW, SH = 1440, 3168
FRAC = 0.15
VIEW = 0.30          # 画布再缩到这个比例方便看
VW, VH = int(SW * VIEW), int(SH * VIEW)

COMPENSATE = 1406 / 917      # 头宽比 = 边框素材需要额外放大的倍数


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


def sized(im, frac):
    th = SH * frac
    r = th / im.height
    return im.resize((max(1, round(im.width * r)), max(1, round(th))), Image.LANCZOS)


def mini(im):
    return im.resize((max(1, round(im.width * VIEW)), max(1, round(im.height * VIEW))), Image.LANCZOS)


def caption(im, text, sub=""):
    im = im.copy()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 56 if sub else 36], fill=(15, 15, 18, 245))
    d.text((8, 7), text, fill=(255, 255, 255, 255))
    if sub:
        d.text((8, 27), sub, fill=(150, 205, 255, 255))
    return im


def main():
    corner = Image.open(ROOT / "右下角" / f"{NAME}.png").convert("RGBA")
    border = Image.open(ROOT / "右边框" / f"{NAME}.png").convert("RGBA")

    hw_c, hw_b = head_width(corner), head_width(border)
    print(f"头宽: corner={hw_c}  border={hw_b}  ->  补偿倍数={hw_c/hw_b:.3f}")

    panels = []

    # 1 右下角：原图，右+下紧贴
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    c = mini(sized(corner, FRAC))
    s.alpha_composite(c, (VW - c.width, VH - c.height))
    panels.append(caption(s, "1. CORNER  (右下角, as-is)",
                          "artwork flush right+bottom"))

    # 2 右边框 rotate(+90) 头朝左（= 你文字描述的那个操作）
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    b = mini(sized(border.rotate(90, expand=True), FRAC * COMPENSATE))
    s.alpha_composite(b, (VW - b.width, (VH - b.height) // 2))
    panels.append(caption(s, "2. BORDER rotate(+90)", "head -> LEFT   (头朝左)"))

    # 3 右边框 rotate(-90) 头朝右
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    b = mini(sized(border.rotate(-90, expand=True), FRAC * COMPENSATE))
    s.alpha_composite(b, (VW - b.width, (VH - b.height) // 2))
    panels.append(caption(s, "3. BORDER rotate(-90)", "head -> RIGHT  (头朝右)"))

    pad = 12
    canvas = Image.new("RGBA", ((VW + pad) * 3 + pad, VH + pad * 2), (18, 19, 22, 255))
    for i, p in enumerate(panels):
        canvas.alpha_composite(p, (pad + i * (VW + pad), pad))
    canvas.save(OUT)
    print("saved:", OUT, canvas.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
