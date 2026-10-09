"""白底去背 —— 抗锯齿（halo）压力测试

上一张 demo 用的是占位角色「团子」，它是扁平硬边美术
（alpha 只有 0 / 80 / 255 三种值），所以扣白看起来很干净，**不能代表真实素材**。

真实的桌宠素材（手绘 / AI 出图 / 缩放过的图）边缘是抗锯齿的：
边缘像素是「角色色 × α + 白色 × (1-α)」的混合。
抠掉背景后，这些像素仍然是**白色混合后的颜色**，于是在深色壁纸上就成了一圈白边。

本脚本合成一个抗锯齿圆形来复现这个问题，并对比两种处理：
  A. 只扣背景，不管边缘        -> 留白边
  B. 扣背景 + 边缘去白化       -> 白边被压掉
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

OUT = Path(r"D:\dsh\dsh work\pet-app\docs\whitebg-halo-test.png")

PET_COLOR = (60, 170, 235)   # 假设角色是纯色
BG = (255, 255, 255)


def make_antialiased_source(size=(200, 200), supersample=4):
    """画一个放大 4 倍再缩小的圆 —— 这样边缘就是真正的抗锯齿。"""
    big = Image.new("RGBA", (size[0] * supersample, size[1] * supersample), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.ellipse([10 * supersample, 10 * supersample,
               (size[0] - 10) * supersample, (size[1] - 10) * supersample],
              fill=PET_COLOR + (255,))
    return big.resize(size, Image.LANCZOS)


def flatten(im, bg=BG):
    out = Image.new("RGBA", im.size, bg + (255,))
    out.alpha_composite(im)
    return out


def remove_bg_only(flat, tol=10):
    """只把与边缘连通的白色置为透明，不动其余像素的颜色。"""
    im = flat.copy()
    px = im.load()
    w, h = im.size
    from collections import deque
    seen = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            r, g, b, _ = px[x, y]
            if r >= 255 - tol and g >= 255 - tol and b >= 255 - tol:
                seen[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            r, g, b, _ = px[x, y]
            if r >= 255 - tol and g >= 255 - tol and b >= 255 - tol and not seen[y * w + x]:
                seen[y * w + x] = 1
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not seen[i]:
                    r, g, b, _ = px[nx, ny]
                    if r >= 255 - tol and g >= 255 - tol and b >= 255 - tol:
                        seen[i] = 1
                        q.append((nx, ny))
    for y in range(h):
        for x in range(w):
            if seen[y * w + x]:
                r, g, b, _ = px[x, y]
                px[x, y] = (r, g, b, 0)
    return im


def unmix_white(im, tol=10, keep_alpha=0.0):
    """扣背景 + 边缘去白化（un-matte）。

    对每个仍有 alpha 的像素，假设它 = 原色*α + 白*(1-α)（α 用 alpha 通道近似），
    反解原色：  orig = (observed - 255*(1-α)) / α
    这能把"被白底冲淡"的边缘颜色还原回角色本色，从而消掉白边。
    """
    im = im.copy()
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            af = a / 255.0
            if af <= 0.004:
                px[x, y] = (r, g, b, 0)
                continue
            # 只对"偏白"的像素做还原，避免破坏本来就正确的实心色
            if r >= 255 - 90 and g >= 255 - 90 and b >= 255 - 90 and af < 1.0:
                nr = int(round((r - 255 * (1 - af)) / af))
                ng = int(round((g - 255 * (1 - af)) / af))
                nb = int(round((b - 255 * (1 - af)) / af))
                px[x, y] = (max(0, min(255, nr)), max(0, min(255, ng)),
                            max(0, min(255, nb)), a)
    return im


def checker(im, size=12, dark=True):
    """深色棋盘格 —— 白边在深色上最明显。"""
    im = im.convert("RGBA")
    w, h = im.size
    a = (35, 38, 45, 255) if dark else (200, 200, 205, 255)
    b = (55, 60, 70, 255) if dark else (170, 172, 178, 255)
    bg = Image.new("RGBA", (w, h), a)
    px = bg.load()
    for y in range(h):
        for x in range(w):
            if ((x // size) + (y // size)) % 2:
                px[x, y] = b
    bg.alpha_composite(im)
    return bg


def label(im, text):
    from PIL import ImageDraw
    im = im.copy()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 20], fill=(15, 15, 18, 255))
    d.text((5, 4), text, fill=(255, 255, 255, 255))
    return im


def main():
    src = make_antialiased_source()
    flat = flatten(src)

    only = remove_bg_only(flat)
    fixed = unmix_white(only)

    # 统计边缘残留白度
    def white_score(im):
        px = im.load()
        tot = 0
        n = 0
        for y in range(im.height):
            for x in range(im.width):
                r, g, b, a = px[x, y]
                if a > 0:
                    tot += (r + g + b) / 3
                    n += 1
        return tot / max(n, 1)

    print(f"原始(透明)边缘平均亮度      : {white_score(src):.1f}")
    print(f"A 只扣背景 剩余像素平均亮度  : {white_score(only):.1f}   <- 越高说明白边越重")
    print(f"B 去白化后 剩余像素平均亮度  : {white_score(fixed):.1f}")

    cells = [
        label(flat, "1. input on white"),
        label(checker(only), "A. bg removed only  (white halo)"),
        label(checker(fixed), "B. bg removed + un-matte"),
        label(checker(src), "reference: true transparent"),
    ]
    pad = 6
    cw = max(c.width for c in cells)
    ch = max(c.height for c in cells)
    canvas = Image.new("RGBA", ((cw + pad) * 2 + pad, (ch + pad) * 2 + pad), (25, 26, 30, 255))
    for i, c in enumerate(cells):
        cx, cy = i % 2, i // 2
        canvas.alpha_composite(c, (pad + cx * (cw + pad), pad + cy * (ch + pad)))
    canvas = canvas.resize((canvas.width * 2, canvas.height * 2), Image.NEAREST)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT)
    print(f"对比图: {OUT}  {canvas.size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
