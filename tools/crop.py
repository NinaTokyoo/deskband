"""裁一块屏幕区域放大保存，用于肉眼核对素材朝向。

用法: python tools/crop.py <src.png> <x> <y> <w> <h> <out.png> [scale]
"""
import sys
from PIL import Image


def main() -> None:
    src, x, y, w, h, out = sys.argv[1:7]
    scale = float(sys.argv[7]) if len(sys.argv) > 7 else 1.0
    im = Image.open(src).convert("RGB")
    box = (int(x), int(y), int(x) + int(w), int(y) + int(h))
    crop = im.crop(box)
    if scale != 1.0:
        crop = crop.resize((int(crop.width * scale), int(crop.height * scale)), Image.LANCZOS)
    crop.save(out)
    print(f"{out} {crop.size[0]}x{crop.size[1]} from {box}")


if __name__ == "__main__":
    main()
