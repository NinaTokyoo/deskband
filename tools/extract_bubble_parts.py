# -*- coding: utf-8 -*-
"""把用户给的气泡原图（bubble.jpg）处理成 App 可用的三个独立素材。

流程：
  1. 顺时针旋转 90°（与合成图一致）；
  2. 从四角 flood fill 掉外部白底，得到"气泡环 + 环内白底"的透明蒙版（顺带去掉水印）；
  3. 连通域分析 -> 恰好 3 个部件（小圈 / 中圈 / 大气泡）；
  4. 各自按外接框裁切，导出 PNG（含 alpha）；
  5. 打印三块在**整组外接框**里的相对位置（比例），Kotlin 侧按这些常量摆位。

输出：app/src/main/assets/bubble/{b0_small,b1_mid,b2_big}.png + 屏幕用的比例常量
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SRC = r"D:\dsh\dsh work\bubble.jpg"
DST = r"D:\dsh\dsh work\pet-app-2.0.25\app\src\main\assets\bubble"
REF = r"D:\dsh\dsh work\deskband-bubble-feature"

# 目标：整组外接框的宽度 = 0.99219 × 人物正立素材宽度（记录基线）
GROUP_W_RATIO = 0.99219
# 导出用的像素尺度：按"人物约 300px 宽"估算，整组约 298px；给 3 倍余量
EXPORT_GROUP_W = 900


def rotate_cw(im):
    return im.transpose(Image.ROTATE_270)          # 270° 逆时针 = 顺时针 90°


def build_mask(im_rgb):
    filled = im_rgb.copy()
    ImageDraw.floodfill(filled, (0, 0), (255, 0, 255), thresh=200)
    a = np.array(filled)
    outside = (a[:, :, 0] == 255) & (a[:, :, 1] == 0) & (a[:, :, 2] == 255)
    keep = ~outside
    m = Image.fromarray((keep * 255).astype(np.uint8))
    m = m.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))   # 去水印细线
    return np.array(m) > 127


def label(mask):
    """四连通连通域标记（纯 numpy + 栈，图片不大，够用）"""
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    cur = 0
    for y0 in range(h):
        row = mask[y0]
        for x0 in range(w):
            if row[x0] and lab[y0, x0] == 0:
                cur += 1
                stack = [(y0, x0)]
                lab[y0, x0] = cur
                while stack:
                    y, x = stack.pop()
                    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        ny, nx = y + dy, x + dx
                        if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and lab[ny, nx] == 0:
                            lab[ny, nx] = cur
                            stack.append((ny, nx))
    return lab, cur


def main():
    src = Image.open(SRC).convert("RGB")
    rot = rotate_cw(src)
    mask = build_mask(rot)
    rgba = rot.convert("RGBA")
    rgba.putalpha(Image.fromarray((mask * 255).astype(np.uint8)))

    lab, n = label(mask)
    print("连通域数量:", n)
    comps = []
    for i in range(1, n + 1):
        ys, xs = np.nonzero(lab == i)
        area = len(xs)
        box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
        comps.append({"area": area, "box": box})
    comps.sort(key=lambda c: c["area"])
    for c in comps:
        b = c["box"]
        print(f"  area={c['area']:>8}  box={b}  {b[2]-b[0]}x{b[3]-b[1]}")

    assert len(comps) == 3, "期望恰好 3 个部件"

    # 整组外接框
    L = min(c["box"][0] for c in comps)
    T = min(c["box"][1] for c in comps)
    R = max(c["box"][2] for c in comps)
    B = max(c["box"][3] for c in comps)
    gw, gh = R - L, B - T
    print(f"整组外接框: ({L},{T})-({R},{B})  {gw}x{gh}  纵横比={gw/gh:.5f}")

    os.makedirs(DST, exist_ok=True)
    names = ["b0_small", "b1_mid", "b2_big"]
    scale = EXPORT_GROUP_W / gw
    consts = []
    for name, c in zip(names, comps):
        x0, y0, x1, y1 = c["box"]
        part = rgba.crop((x0, y0, x1, y1))
        tw = max(1, int(round((x1 - x0) * scale)))
        th = max(1, int(round((y1 - y0) * scale)))
        part.resize((tw, th), Image.LANCZOS).save(os.path.join(DST, name + ".png"))
        # 相对整组的比例（0..1），供 Kotlin 摆位
        fx, fy = (x0 - L) / gw, (y0 - T) / gh
        fw, fh = (x1 - x0) / gw, (y1 - y0) / gh
        consts.append((name, fx, fy, fw, fh, tw, th))
        print(f"{name}: 相对整组 x={fx:.5f} y={fy:.5f} w={fw:.5f} h={fh:.5f} -> 导出 {tw}x{th}")

    lines = ["// ==== 由 tools/extract_bubble_parts.py 生成，勿手改 ====",
             "// 三个部件在整组外接框里的相对矩形（左上角 + 宽高，0..1）",
             "private val PART_BOX = arrayOf("]
    for name, fx, fy, fw, fh, tw, th in consts:
        lines.append(f"    floatArrayOf({fx:.5f}f, {fy:.5f}f, {fw:.5f}f, {fh:.5f}f),   // {name}")
    lines.append(")")
    lines.append(f"// 整组宽 / 人物正立素材宽 = {GROUP_W_RATIO}")
    lines.append(f"// 整组纵横比 = {gw/gh:.5f}")
    out = "\n".join(lines) + "\n"
    with open(os.path.join(REF, "generated_consts.kt"), "w", encoding="utf-8") as f:
        f.write(out)
    print("\n" + out)


if __name__ == "__main__":
    main()
