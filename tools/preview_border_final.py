"""右边框朝向定稿：两种旋转 + 面部位置标注

用实测数据代替猜测。脸的实测重心（相对坐标，0=左/上, 1=右/下）：
    后藤一里  右下角 x=0.54 y=0.65
旋转 90° 后脸会跑到哪（数学，不是肉眼判断）：
    顺时针 rotate(-90):  x' = 1 - y = 0.35   -> 脸在左半边
    逆时针 rotate(+90):  x' = y     = 0.65   -> 脸在右半边

因为素材是紧贴裁剪的，旋转后贴住屏幕右侧，
"脸在左/右" 决定了人物是"面朝屏幕内侧躺着"还是"脸贴在边缘被切掉"。
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
OUT = Path(r"D:\dsh\dsh work\pet-app\docs\border-orientation-final.png")

NAME = "后藤一里"
SW, SH = 1440, 3168
FRAC = 0.15
COMP = 1406 / 917          # 头宽比补偿
VIEW = 0.34
VW, VH = int(SW * VIEW), int(SH * VIEW)


def face_centroid(im):
    px = im.load()
    w, h = im.size
    xs, ys = [], []
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b, a = px[x, y]
            if a > 200 and r > 235 and g > 205 and b > 195 and r >= g >= b and (r - b) < 45:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return sum(xs) / len(xs) / w, sum(ys) / len(ys) / h


def scale_to_h(im, target_h):
    r = target_h / im.height
    return im.resize((max(1, round(im.width * r)), max(1, round(target_h))), Image.LANCZOS)


def mini(im):
    return im.resize((max(1, round(im.width * VIEW)), max(1, round(im.height * VIEW))), Image.LANCZOS)


def caption(im, text, sub="", sub2=""):
    im = im.copy()
    d = ImageDraw.Draw(im)
    hh = 76 if sub2 else (58 if sub else 38)
    d.rectangle([0, 0, im.width, hh], fill=(15, 15, 18, 248))
    d.text((8, 8), text, fill=(255, 255, 255, 255))
    if sub:
        d.text((8, 28), sub, fill=(150, 205, 255, 255))
    if sub2:
        d.text((8, 48), sub2, fill=(255, 205, 120, 255))
    return im


def mark_face(im, fx, fy):
    """在缩放后的图上标出脸的位置。"""
    d = ImageDraw.Draw(im)
    x, y = int(fx * im.width), int(fy * im.height)
    r = max(9, im.width // 16)
    d.ellipse([x - r, y - r, x + r, y + r], outline=(255, 60, 60, 255), width=3)
    d.line([(x - r - 8, y), (x + r + 8, y)], fill=(255, 60, 60, 255), width=2)
    d.line([(x, y - r - 8), (x, y + r + 8)], fill=(255, 60, 60, 255), width=2)
    return im


def main():
    corner = Image.open(ROOT / "右下角" / f"{NAME}.png").convert("RGBA")
    border = Image.open(ROOT / "右边框" / f"{NAME}.png").convert("RGBA")

    fx, fy = face_centroid(border)
    print(f"{NAME} border 脸重心: x={fx:.3f} y={fy:.3f}")
    print(f"  旋转后: 顺时针脸x'={1-fy:.3f}   逆时针脸x'={fy:.3f}\n")

    panels = []

    # 1 corner 参考
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    c = mini(scale_to_h(corner, SH * FRAC))
    s.alpha_composite(c, (VW - c.width, VH - c.height))
    panels.append(caption(s, "1. CORNER (reference)", "upright, flush R+B"))

    # 2 顺时针 rotate(-90)
    b_cw = border.rotate(-90, expand=True)
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    b = mini(scale_to_h(b_cw, SH * FRAC * COMP))
    px, py = VW - b.width, (VH - b.height) // 2
    s.alpha_composite(b, (px, py))
    f = (1 - fy, fx)                     # 顺时针：x'=1-y, y'=x
    s = mark_face(s, (px + f[0] * b.width) / VW, (py + f[1] * b.height) / VH)
    panels.append(caption(s, "2. rotate(-90) = CLOCKWISE",
                          f"face -> x'={f[0]:.2f} (左侧)", "红圈 = 脸的位置"))

    # 3 逆时针 rotate(+90)
    b_ccw = border.rotate(90, expand=True)
    s = Image.new("RGBA", (VW, VH), (30, 32, 38, 255))
    b = mini(scale_to_h(b_ccw, SH * FRAC * COMP))
    px, py = VW - b.width, (VH - b.height) // 2
    s.alpha_composite(b, (px, py))
    f = (fy, 1 - fx)                     # 逆时针：x'=y, y'=1-x
    s = mark_face(s, (px + f[0] * b.width) / VW, (py + f[1] * b.height) / VH)
    panels.append(caption(s, "3. rotate(+90) = COUNTER-CW",
                          f"face -> x'={f[0]:.2f} (右侧)", "红圈 = 脸的位置"))

    pad = 12
    canvas = Image.new("RGBA", ((VW + pad) * 3 + pad, VH + pad * 2), (18, 19, 22, 255))
    for i, p in enumerate(panels):
        canvas.alpha_composite(p, (pad + i * (VW + pad), pad))
    canvas.save(OUT)
    print("saved:", OUT, canvas.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
