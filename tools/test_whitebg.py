"""白底去背可行性验证（demo）

拿现有占位角色「团子」的一帧做实验：
  1. 原图带透明通道
  2. 把原图压成纯白背景 —— 模拟"客户给的白底图"
  3. 方法A：全局白色阈值扣白（很多人第一反应的做法）
  4. 方法B：从画布边缘 flood fill 只扣"连通到外围"的白（本方案）

输出一张 2x2 对比图，用来肉眼判断：
  - 方法A 有没有把角色内部的白色也挖空（破洞）
  - 边缘有没有留下白色残余（白边/halo）
"""
import sys
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ASSETS = Path(r"D:\dsh\dsh work\pet-app\app\src\main\assets\pet\default")
OUT = Path(r"D:\dsh\dsh work\pet-app\docs\whitebg-demo.png")


def flatten_on_white(im: Image.Image) -> Image.Image:
    """把带透明的图压到纯白背景上。"""
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    bg.alpha_composite(im.convert("RGBA"))
    return bg


def white_to_alpha_global(im: Image.Image, tol: int = 16) -> Image.Image:
    """方法A：全局阈值。任何接近纯白的像素一律透明。

    问题：角色自己身上的白色（白肚皮、高光、白眼睛）也会被扣掉。
    """
    im = im.convert("RGBA")
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if r >= 255 - tol and g >= 255 - tol and b >= 255 - tol:
                px[x, y] = (r, g, b, 0)
    return im


def white_to_alpha_flood(im: Image.Image, tol: int = 10,
                         feather: bool = True) -> Image.Image:
    """方法B：从四边 flood fill，只扣与画布边缘连通的白色。

    角色内部的白色区域（被轮廓包住的）与边缘不连通，因此**不会被扣掉**。
    再做一圈 alpha 羽化，消掉抗锯齿留下的白色描边。
    """
    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()

    def is_white(x, y) -> bool:
        r, g, b, _ = px[x, y]
        return r >= 255 - tol and g >= 255 - tol and b >= 255 - tol

    # BFS 从四边所有白像素出发，标记"外围白"
    seen = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_white(x, y) and not seen[y * w + x]:
                seen[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_white(x, y) and not seen[y * w + x]:
                seen[y * w + x] = 1
                q.append((x, y))

    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not seen[i] and is_white(nx, ny):
                    seen[i] = 1
                    q.append((nx, ny))

    for y in range(h):
        base = y * w
        for x in range(w):
            if seen[base + x]:
                r, g, b, _ = px[x, y]
                px[x, y] = (r, g, b, 0)

    if feather:
        # 用 alpha 做一次轻微模糊，压掉残留白边
        alpha = im.getchannel("A")
        alpha = alpha.filter(ImageFilter.GaussianBlur(0.5))
        im.putalpha(alpha)
    return im


def checker(im: Image.Image, size: int = 16) -> Image.Image:
    """给透明图垫上棋盘格，方便肉眼看透明区域。"""
    im = im.convert("RGBA")
    w, h = im.size
    bg = Image.new("RGBA", (w, h), (255, 255, 255, 255))
    px = bg.load()
    for y in range(h):
        for x in range(w):
            if ((x // size) + (y // size)) % 2:
                px[x, y] = (205, 205, 210, 255)
    bg.alpha_composite(im)
    return bg


def label(im: Image.Image, text: str) -> Image.Image:
    im = im.copy()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 22], fill=(20, 20, 24, 255))
    d.text((6, 5), text, fill=(255, 255, 255, 255))
    return im


def main() -> int:
    frames = sorted((ASSETS / "idle").glob("*.png"))
    if not frames:
        print("找不到 idle 帧", file=sys.stderr)
        return 1
    src = Image.open(frames[0]).convert("RGBA")
    print(f"样本: {frames[0].name}  尺寸={src.size}")

    # 统计原图里"接近纯白"的像素有多少是角色本体（alpha>0）
    px = src.load()
    solid_white = sum(
        1 for y in range(src.height) for x in range(src.width)
        if px[x, y][3] > 0 and all(c >= 239 for c in px[x, y][:3])
    )
    solid = sum(1 for y in range(src.height) for x in range(src.width) if px[x, y][3] > 0)
    print(f"原图不透明像素={solid}  其中接近纯白的={solid_white} "
          f"({solid_white / max(solid, 1) * 100:.2f}%)")
    print("↑ 这个比例就是『全局扣白』会误伤的像素占比；flood fill 方案下它们会被保住")

    flat = flatten_on_white(src)
    a = white_to_alpha_global(flat)
    b = white_to_alpha_flood(flat)

    pad = 8
    cell_w, cell_h = src.width, src.height
    canvas = Image.new("RGBA", (cell_w * 2 + pad * 3, (cell_h + 26) * 2 + pad * 3),
                       (40, 42, 48, 255))
    cells = [
        (0, 0, label(flat, "1. input: white background")),
        (1, 0, label(checker(a), "2. global threshold (punches holes)")),
        (0, 1, label(checker(b), "3. edge flood-fill (keeps inner white)")),
        (1, 1, label(checker(src), "4. original alpha (reference)")),
    ]
    for cx, cy, im in cells:
        canvas.alpha_composite(im, (pad + cx * (cell_w + pad), pad + cy * (cell_h + 26 + pad)))

    canvas = canvas.resize((canvas.width * 2, canvas.height * 2), Image.NEAREST)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT)
    print(f"对比图已写出: {OUT}  ({canvas.size[0]}x{canvas.size[1]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
