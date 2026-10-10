"""量宠物内容的实际屏幕位置（靠高饱和像素识别），用于验证"是否沿边框往下走"。

用法: python tools/pet_bbox.py <shot.png> [区域 x0 y0 x1 y1]
"""
import sys
from PIL import Image


def bbox(path, box=None):
    im = Image.open(path).convert("RGB")
    if box:
        im = im.crop(box)
        ox, oy = box[0], box[1]
    else:
        ox, oy = 0, 0
    px = im.load()
    minx = miny = 10 ** 9
    maxx = maxy = -1
    for y in range(0, im.height, 2):
        for x in range(0, im.width, 2):
            r, g, b = px[x, y]
            if max(r, g, b) - min(r, g, b) > 45 and max(r, g, b) > 120:
                gx, gy = x + ox, y + oy
                if gx < minx:
                    minx = gx
                if gx > maxx:
                    maxx = gx
                if gy < miny:
                    miny = gy
                if gy > maxy:
                    maxy = gy
    return (minx, miny, maxx, maxy) if maxx >= 0 else None


def main() -> None:
    path = sys.argv[1]
    box = None
    if len(sys.argv) >= 6:
        box = tuple(int(v) for v in sys.argv[2:6])
    b = bbox(path, box)
    if b is None:
        print("no pet pixels")
    else:
        print(f"content x[{b[0]},{b[2]}] y[{b[1]},{b[3]}]  top={b[1]} bottom={b[3]} h={b[3]-b[1]+1}")


if __name__ == "__main__":
    main()
