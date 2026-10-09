"""决定性验证：边框素材到底该怎么转 + 两套素材是不是同一张画

之前在小图上反复推断旋转方向容易出错，这里改用两个硬指标：

A) 把「右下角」原图按比例缩到和「右边框」同尺寸，逐像素比相似度。
   - 如果高度相似 -> 两套就是同一张画的两个分辨率版本（那"旋转"只是渲染问题）
   - 如果差异很大 -> 是两套独立的画，必须分别对待

B) 把两种旋转结果放大到占满画面，并画出"头顶"标记，让肉眼一眼定方向。
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
OUT = Path(r"D:\dsh\dsh work\pet-app\docs\rotation-decide.png")

NAME = "后藤一里"


def diff_score(a: Image.Image, b: Image.Image) -> float:
    """两张同尺寸 RGBA 的平均通道差（0 = 完全一样）。"""
    if a.size != b.size:
        b = b.resize(a.size, Image.LANCZOS)
    d = ImageChops.difference(a.convert("RGBA"), b.convert("RGBA"))
    px = d.load()
    w, h = d.size
    tot = 0
    n = 0
    for y in range(0, h, 3):
        for x in range(0, w, 3):
            r, g, bl, al = px[x, y]
            tot += (r + g + bl + al) / 4
            n += 1
    return tot / max(n, 1)


def caption(im, text, sub=""):
    im = im.copy()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 52 if sub else 34], fill=(15, 15, 18, 245))
    d.text((8, 7), text, fill=(255, 255, 255, 255))
    if sub:
        d.text((8, 26), sub, fill=(150, 205, 255, 255))
    return im


def fit(im: Image.Image, box: int) -> Image.Image:
    r = min(box / im.width, box / im.height)
    return im.resize((max(1, round(im.width * r)), max(1, round(im.height * r))), Image.LANCZOS)


def mark(im: Image.Image, color=(255, 60, 60, 255)) -> Image.Image:
    """在图像内容的最顶部画一条横杠 = 原始"头顶"所在边。"""
    im = im.copy()
    d = ImageDraw.Draw(im)
    y = max(1, im.height // 40)
    d.line([(0, y), (im.width, y)], fill=color, width=max(2, im.height // 90))
    return im


def checker(im, size=14):
    im = im.convert("RGBA")
    w, h = im.size
    bg = Image.new("RGBA", (w, h), (245, 245, 248, 255))
    px = bg.load()
    for y in range(h):
        for x in range(w):
            if ((x // size) + (y // size)) % 2:
                px[x, y] = (205, 205, 212, 255)
    bg.alpha_composite(im)
    return bg


def main():
    corner = Image.open(ROOT / "右下角" / f"{NAME}.png").convert("RGBA")
    border = Image.open(ROOT / "右边框" / f"{NAME}.png").convert("RGBA")

    print(f"corner={corner.size}  border={border.size}")

    # --- A) 同一张画吗？ ---
    a = corner.resize(border.size, Image.LANCZOS)
    s_same = diff_score(a, border)
    s_rot_cw = diff_score(a.rotate(-90, expand=True), border)
    s_rot_ccw = diff_score(a.rotate(90, expand=True), border)
    print("\n=== A) 与 border 的平均像素差（越小越像，0=完全相同）===")
    print(f"  corner 直接缩放到 border 尺寸      : {s_same:8.2f}")
    print(f"  corner 缩放后再顺时针90°           : {s_rot_cw:8.2f}")
    print(f"  corner 缩放后再逆时针90°           : {s_rot_ccw:8.2f}")
    best = min(s_same, s_rot_cw, s_rot_ccw)
    which = {s_same: "直接缩放(同向)", s_rot_cw: "顺时针90", s_rot_ccw: "逆时针90"}[best]
    print(f"  -> 最接近的是: {which}（差 {best:.2f}）")

    # --- B) 放大看方向 ---
    BOX = 470
    c = mark(checker(fit(corner, BOX)))
    r_cw = mark(checker(fit(border.rotate(-90, expand=True), BOX)))
    r_ccw = mark(checker(fit(border.rotate(90, expand=True), BOX)))
    note = "红线 = 原图「顶边」位置"
    panels = [
        caption(c, "A. CORNER original", note + " (头在红线上)"),
        caption(r_cw, "B. BORDER rotate(-90)=顺时针", "红线转到哪边，头就朝哪边"),
        caption(r_ccw, "C. BORDER rotate(+90)=逆时针", "红线转到哪边，头就朝哪边"),
    ]
    pad = 10
    w = sum(p.width for p in panels) + pad * (len(panels) + 1)
    h = max(p.height for p in panels) + pad * 2
    canvas = Image.new("RGBA", (w, h), (18, 19, 22, 255))
    x = pad
    for p in panels:
        canvas.alpha_composite(p, (x, pad))
        x += p.width + pad
    canvas.save(OUT)
    print(f"\nsaved: {OUT}  {canvas.size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
