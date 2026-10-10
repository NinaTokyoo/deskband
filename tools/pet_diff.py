"""比较两张截图，给出**变化像素**的外接框。

用来量"压缩动画"这类只在宠物身上发生的变化 —— 背景是什么颜色都无所谓，
因为只有宠物占了像素差异。用法：

    python tools/pet_diff.py before.png after.png [x0 y0 x1 y1]
"""
import sys
from PIL import Image


def diff_bbox(p1, p2, box=None):
    a = Image.open(p1).convert("RGB")
    b = Image.open(p2).convert("RGB")
    if box:
        a = a.crop(box)
        b = b.crop(box)
        ox, oy = box[0], box[1]
    else:
        ox, oy = 0, 0
    if a.size != b.size:
        return None, 0
    pa, pb = a.load(), b.load()
    minx = miny = 10 ** 9
    maxx = maxy = -1
    n = 0
    for y in range(a.height):
        for x in range(a.width):
            r1, g1, b1 = pa[x, y]
            r2, g2, b2 = pb[x, y]
            if abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) > 24:
                n += 1
                gx, gy = x + ox, y + oy
                if gx < minx:
                    minx = gx
                if gx > maxx:
                    maxx = gx
                if gy < miny:
                    miny = gy
                if gy > maxy:
                    maxy = gy
    if maxx < 0:
        return None, 0
    return (minx, miny, maxx, maxy), n


def main() -> None:
    box = None
    if len(sys.argv) >= 6:
        box = tuple(int(v) for v in sys.argv[3:7])
    b, n = diff_bbox(sys.argv[1], sys.argv[2], box)
    if b is None:
        print(f"no change (diff pixels={n})")
        return
    print(
        f"changed x[{b[0]},{b[2]}] y[{b[1]},{b[3]}] "
        f"w={b[2]-b[0]+1} h={b[3]-b[1]+1} pixels={n}"
    )


if __name__ == "__main__":
    main()
