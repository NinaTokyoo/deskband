"""左侧素材生成 —— 修正版（含朝向证明）

关键坑：**水平镜像会翻转"顺时针"的含义**。
所以"右边框逆时针90°" 不能简单照搬成 "左边框顺/逆时针90°"，必须重新推导。

推导（用实测的脸重心，相对坐标 x,y ∈ [0,1]）：
  记号：mirror(x,y) = (1-x, y)
        顺时针90° = PIL rotate(-90): (x,y) -> (1-y, x)
        逆时针90° = PIL rotate(+90): (x,y) -> (y, 1-x)

  右边框：源脸 x=0.48 y=0.68
      逆时针90° -> (0.68, 0.52) -> 贴到屏幕右侧后，脸 x=0.68 靠右 = 朝屏幕内侧 ✓

  左边框候选A（镜像源 + 顺时针90°）：
      (0.52, 0.68) -> 顺时针 -> (1-0.68, 0.52) = (0.32, 0.52) -> 贴左侧，脸 x=0.32 靠左 ✗（朝屏幕外侧）
  左边框候选B（镜像源 + 逆时针90°）：
      (0.52, 0.68) -> 逆时针 -> (0.68, 1-0.52) = (0.68, 0.48) -> 贴左侧，脸 x=0.68 靠右 = 朝屏幕内侧 ✓

  => 左边框也必须用**逆时针90°**，并且为了保持人物上下不颠倒，
     左侧真正的做法是：先在像素上做「镜像 + 上下翻转」，再逆时针90°。
     下面同时验证这条路。
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
OUT = Path(r"D:\dsh\dsh work\pet-app\docs\left-mirror-check.png")

NAMES = ["伊地知虹夏", "后藤一里", "喜多郁代", "山田凉", "广井菊里"]
CHECK = "后藤一里"


def face_centroid(im):
    px = im.load()
    w, h = im.size
    xs, ys = [], []
    for y in range(0, h, 3):
        for x in range(0, w, 3):
            r, g, b, a = px[x, y]
            if a > 200 and r > 235 and g > 205 and b > 195 and r >= g >= b and (r - b) < 45:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return sum(xs) / len(xs) / w, sum(ys) / len(ys) / h


def right_edge_fill(im, frac=0.80):
    px = im.load()
    w, h = im.size
    x = int(w * frac)
    col = [y for y in range(0, h, 3) if px[x, y][3] > 16]
    return len(col) / max(1, len(range(0, h, 3)))


def build_left(src_right: Image.Image) -> Image.Image:
    """右侧原图 -> 左侧素材（像素级：镜像 + 上下翻转）。"""
    return ImageOps.flip(ImageOps.mirror(src_right))


def generate():
    for name in NAMES:
        for src_state, dst_state in (("右边框", "左边框"), ("右下角", "左下角")):
            src = ROOT / src_state / f"{name}.png"
            dst = ROOT / dst_state / f"{name}.png"
            if not src.exists():
                print(f"  !! 缺少 {src}")
                continue
            im = Image.open(src).convert("RGBA")
            build_left(im).save(dst)
            print(f"  {src_state}/{name}.png -> {dst_state}/{name}.png  {im.size[0]}x{im.size[1]}")


def main():
    print("=== 生成左侧素材（镜像+上下翻转）===")
    generate()

    right = Image.open(ROOT / "右边框" / f"{CHECK}.png").convert("RGBA")
    left = Image.open(ROOT / "左边框" / f"{CHECK}.png").convert("RGBA")
    r_corner = Image.open(ROOT / "右下角" / f"{CHECK}.png").convert("RGBA")
    l_corner = Image.open(ROOT / "左下角" / f"{CHECK}.png").convert("RGBA")

    fx, fy = face_centroid(right)
    print(f"\n=== {CHECK} 朝向证明 ===")
    print(f"  右边框源: 脸 x={fx:.2f} y={fy:.2f}")

    # 右侧：逆时针90
    r_rot = right.rotate(90, expand=True)
    fr = (fy, 1 - fx)
    print(f"  右边框 逆时针90° -> 脸 ({fr[0]:.2f},{fr[1]:.2f})   贴右侧后脸在 x={fr[0]:.2f} (越大越靠内)")
    print(f"      右缘填充={right_edge_fill(r_rot):.2f} (1.0 = 内容顶到右缘)")

    # 左侧：镜像+上下翻转 后 逆时针90
    l_rot = left.rotate(90, expand=True)
    lfx, lfy = face_centroid(left)
    fl = (lfy, 1 - lfx)
    print(f"  左边框 逆时针90° -> 脸 ({fl[0]:.2f},{fl[1]:.2f})   贴左侧后脸在 x={fl[0]:.2f} (越大越靠内)")
    print(f"      左缘填充={1-right_edge_fill(ImageOps.mirror(l_rot)):.2f}")

    # 上下不颠倒校验：脸的 y' 应与右侧一致
    print(f"\n  上下校验: 右侧脸 y'={fr[1]:.2f}   左侧脸 y'={fl[1]:.2f}   "
          f"差={abs(fr[1]-fl[1]):.2f} (接近0 = 人物没有上下颠倒)")

    def sc(im, th=340):
        r = th / im.height
        return im.resize((max(1, round(im.width * r)), th), Image.LANCZOS)

    def cap(im, t1, t2=""):
        im = im.copy()
        d = ImageDraw.Draw(im)
        d.rectangle([0, 0, im.width, 54 if t2 else 34], fill=(15, 15, 18, 245))
        d.text((7, 6), t1, fill=(255, 255, 255, 255))
        if t2:
            d.text((7, 26), t2, fill=(150, 205, 255, 255))
        return im

    cells = [
        cap(sc(right), "RIGHT src", "upright"),
        cap(sc(right.rotate(90, expand=True)), "RIGHT + CCW90", "-> right edge"),
        cap(sc(left), "LEFT src", "mirror+flip"),
        cap(sc(left.rotate(90, expand=True)), "LEFT + CCW90", "-> left edge"),
    ]
    pad = 8
    W = sum(c.width for c in cells) + pad * (len(cells) + 1)
    H = max(c.height for c in cells) + pad * 2
    canvas = Image.new("RGBA", (W, H), (20, 21, 25, 255))
    x = pad
    for c in cells:
        canvas.alpha_composite(c, (x, pad))
        x += c.width + pad
    canvas.save(OUT)
    print(f"\nsaved: {OUT} {canvas.size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
