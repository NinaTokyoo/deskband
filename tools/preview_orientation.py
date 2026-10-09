"""朝向 + 大小 二次确认

修正上一版的标注错误：PIL 的 rotate() 是**逆时针为正**，
所以顺时针 90° = rotate(-90)，上一版把两者的标题写反了。

同时量一下两套素材的基准大小差多少 —— 因为文件都是紧贴裁剪（无留白），
「高度归一化」会让两套素材的人物实际大小不一致。
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
OUT = Path(r"D:\dsh\dsh work\pet-app\docs\orientation-preview.png")

NAMES = ["伊地知虹夏", "后藤一里", "喜多郁代", "山田凉", "广井菊里"]
SCREEN_W, SCREEN_H = 1440, 3168
FRAC = 0.15
SCALE = 0.25
CW, CH = int(SCREEN_W * SCALE), int(SCREEN_H * SCALE)


def head_width(im: Image.Image, top_frac: float = 0.40) -> int:
    """量"最宽的头"：取上半部分里最宽的实体行宽 —— 用作两套素材的大小基准。"""
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


def sized(im: Image.Image, frac: float) -> Image.Image:
    """等比缩放到 高度 = 屏幕高度*frac，不裁切。"""
    th = SCREEN_H * frac
    r = th / im.height
    return im.resize((max(1, round(im.width * r)), max(1, round(th))), Image.LANCZOS)


def phone():
    return Image.new("RGBA", (CW, CH), (32, 34, 40, 255))


def put_right_center(screen, im):
    s = im.resize((max(1, round(im.width * SCALE)), max(1, round(im.height * SCALE))), Image.LANCZOS)
    screen.alpha_composite(s, (CW - s.width, (CH - s.height) // 2))


def put_bottom_right(screen, im):
    s = im.resize((max(1, round(im.width * SCALE)), max(1, round(im.height * SCALE))), Image.LANCZOS)
    screen.alpha_composite(s, (CW - s.width, CH - s.height))


def caption(im, text, sub=""):
    im = im.copy()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 50 if sub else 34], fill=(15, 15, 18, 240))
    d.text((6, 6), text, fill=(255, 255, 255, 255))
    if sub:
        d.text((6, 24), sub, fill=(150, 205, 255, 255))
    return im


def main():
    corner = Image.open(ROOT / "右下角" / "后藤一里.png").convert("RGBA")
    border = Image.open(ROOT / "右边框" / "后藤一里.png").convert("RGBA")

    hw_corner = head_width(corner)
    hw_border = head_width(border)
    print(f"后藤一里  头宽基准:  右下角={hw_corner}px(图宽{corner.width})   "
          f"右边框={hw_border}px(图宽{border.width})")
    print(f"  -> 若都按'高度=屏幕15%'渲染，两套人物大小相差 "
          f"{hw_corner/hw_border:.2f} 倍")
    print(f"  -> 要让人物一样大，边框那套需要额外放大 {hw_corner/hw_border:.3f} 倍")

    b_cw = border.rotate(-90, expand=True)   # 顺时针90
    b_ccw = border.rotate(90, expand=True)   # 逆时针90

    panels = []
    s = phone(); put_bottom_right(s, sized(corner, FRAC))
    panels.append(caption(s, "1. CORNER as-is", "artwork flush R+B"))

    s = phone(); put_right_center(s, sized(b_cw, FRAC))
    panels.append(caption(s, "2. BORDER rotate(-90) = CLOCKWISE",
                          "head -> RIGHT edge of screen"))

    s = phone(); put_right_center(s, sized(b_ccw, FRAC))
    panels.append(caption(s, "3. BORDER rotate(+90) = COUNTER-CW",
                          "head -> LEFT edge of screen"))

    # 4：两套都按高度15%，看人物大小差
    s = phone()
    put_bottom_right(s, sized(corner, FRAC))
    put_right_center(s, sized(border, FRAC))   # 未旋转，正立
    panels.append(caption(s, "4. both height=15%, UNROTATED",
                          f"border appears {hw_corner/hw_border:.2f}x smaller"))

    pad = 10
    canvas = Image.new("RGBA", ((CW + pad) * len(panels) + pad, CH + pad * 2), (18, 19, 22, 255))
    for i, p in enumerate(panels):
        canvas.alpha_composite(p, (pad + i * (CW + pad), pad))
    canvas.save(OUT)
    print("saved:", OUT, canvas.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
