"""从真机截图客观判定吸附朝向。

只裁剪宠物所在区域再分析，避免壁纸干扰（壁纸也有粉色）。
格子尺寸按代码计算：
  窗口高 3084（已扣导航栏），sizeFraction=0.15
  角落素材 1440x1352 -> 475x446
  边框素材 1024x875 -> 旋转后 3084*0.15=463 高，宽 463*1024/875=542
吸附贴边时格子 542x463（宽>高 = 躺倒）
"""
from pathlib import Path

from PIL import Image, ImageDraw

DOCS = Path(r"D:\dsh\dsh work\pet-app\docs")
OUT = DOCS / "verify-result.png"

WIN_H = 3084
WIN_W = 1440


def box_snapped():
    w, h = 542, 463
    return (WIN_W - w, (WIN_H - h) // 2, WIN_W, (WIN_H + h) // 2)


def box_detached():
    # 拖出后回到角落逻辑：角落素材比例 1440x1352
    w, h = 475, 446
    return (WIN_W - w, WIN_H - h, WIN_W, WIN_H)


def face_centroid(im):
    """在裁剪区域内找肤色重心（相对坐标）。"""
    px = im.load()
    w, h = im.size
    xs, ys = [], []
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b = px[x, y]
            if r > 235 and g > 205 and b > 195 and r >= g >= b and (r - b) < 45:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return sum(xs) / len(xs) / w, sum(ys) / len(ys) / h, len(xs)


def crop_checker(im, box, tag):
    c = im.crop(box).convert("RGB")
    f = face_centroid(c)
    ok = []
    ok.append(f"尺寸 {c.width}x{c.height}  宽/高={c.width/c.height:.2f}")
    if c.width > c.height:
        ok.append("形态: 躺倒 (宽>高)")
    else:
        ok.append("形态: 正立 (高>宽)")
    if f:
        ok.append(f"脸重心 x={f[0]:.2f} y={f[1]:.2f} (肤色像素 {f[2]})")
        if c.width > c.height:
            ok.append("脸朝内" if f[0] < 0.5 else "脸朝外 <-- 错")
        else:
            ok.append("正立，未旋转")
    else:
        ok.append("没找到肤色像素")
    print(f"[{tag}]")
    for line in ok:
        print("   " + line)
    return c


def checker(im, size=14):
    bg = Image.new("RGB", im.size, (235, 235, 240))
    d = ImageDraw.Draw(bg)
    for y in range(0, im.height, size):
        for x in range(0, im.width, size):
            if ((x // size) + (y // size)) % 2:
                d.rectangle([x, y, x + size - 1, y + size - 1], fill=(200, 200, 208))
    bg.paste(im, (0, 0), im.convert("RGBA"))
    return bg


def main():
    snapped = Image.open(DOCS / "verify-snapped.png").convert("RGBA")
    detached = Image.open(DOCS / "verify-detached.png").convert("RGBA")
    print(f"screenshot {snapped.size}\n")

    a = crop_checker(snapped, box_snapped(), "吸附贴右边")
    print()
    b = crop_checker(detached, box_detached(), "脱开(>80px)")

    pad = 10
    ca, cb = checker(a.resize((a.width // 2, a.height // 2))), \
        checker(b.resize((b.width // 2, b.height // 2)))
    W = ca.width + cb.width + pad * 3
    H = max(ca.height, cb.height) + 60 + pad * 2
    canvas = Image.new("RGB", (W, H), (25, 26, 30))
    d = ImageDraw.Draw(canvas)
    d.text((pad, 8), "SNAPPED to right edge (should be lying, face inward)", fill=(255, 255, 255))
    d.text((pad + ca.width + pad, 8), "DETACHED >80px (should be upright)", fill=(255, 255, 255))
    canvas.paste(ca, (pad, 30 + pad))
    canvas.paste(cb, (pad * 2 + ca.width, 30 + pad))
    canvas.save(OUT)
    print(f"\nsaved {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
