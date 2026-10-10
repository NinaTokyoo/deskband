"""量"宠物内容到屏幕边缘"的实际缝隙。

做法：截图里找宠物的**高饱和**像素（人物是粉发，壁纸是低饱和的灰蓝），
限定在悬浮窗附近搜索，避开状态栏/导航栏与其它 UI。

用法: python tools/edge_gap.py <shot.png> <win_x> <win_y> <win_w> <win_h> <screen_w> <screen_bottom>
"""
import sys
from PIL import Image


def main() -> None:
    shot, wx, wy, ww, wh, sw, sh = sys.argv[1:8]
    wx, wy, ww, wh, sw, sh = int(wx), int(wy), int(ww), int(wh), int(sw), int(sh)
    im = Image.open(shot).convert("RGB")
    # 搜索范围：窗口外扩 40px，并裁掉状态栏(上 140)与导航栏(下)
    x0 = max(0, wx - 40)
    y0 = max(140, wy - 40)
    x1 = min(im.width, wx + ww + 40)
    y1 = min(sh, wy + wh + 40)
    if x1 <= x0 or y1 <= y0:
        print("搜索区域为空")
        return
    crop = im.crop((x0, y0, x1, y1))
    px = crop.load()
    minx, miny, maxx, maxy = 10 ** 9, 10 ** 9, -1, -1
    for y in range(crop.height):
        for x in range(crop.width):
            r, g, b = px[x, y]
            if max(r, g, b) - min(r, g, b) > 45 and max(r, g, b) > 120:
                gx, gy = x + x0, y + y0
                if gx < minx:
                    minx = gx
                if gx > maxx:
                    maxx = gx
                if gy < miny:
                    miny = gy
                if gy > maxy:
                    maxy = gy
    if maxx < 0:
        print("没找到宠物像素")
        return
    print(f"内容 bbox: x[{minx},{maxx}] y[{miny},{maxy}]  尺寸 {maxx-minx+1}x{maxy-miny+1}")
    print(f"  左缝 {minx}   右缝 {sw - 1 - maxx}   上缝 {miny}   下缝 {sh - 1 - maxy}"
          f"   (屏宽 {sw}, 可用底 {sh})")


if __name__ == "__main__":
    main()
