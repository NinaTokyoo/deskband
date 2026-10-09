#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把你自己的素材（GIF / APNG / 动画 WebP / PNG 序列）转成 App 能读的角色皮肤。

用法：
    python tools/import_assets.py                 # 读 ../assets-incoming，皮肤名取 my
    python tools/import_assets.py --name 小猫      # 指定皮肤名
    python tools/import_assets.py --src D:\\图\\宠物 --name cat

素材怎么摆（两种都行）：

  方式 A：一个皮肤一个文件夹
      assets-incoming/小猫/
          idle.gif
          walk.gif
          happy.gif

  方式 B：直接放一堆文件，用文件名当前缀
      assets-incoming/
          idle_00.png  idle_01.png  idle_02.png
          walk.gif
          happy.gif

文件名（不含扩展名）决定这是哪段动画，支持中英文：

  idle / 待机 / stand      待机（循环）
  blink / 眨眼             眨眼（播一次）
  happy / 开心 / click     被点一下（播一次）
  drag / 被拖 / hold       被拎起来（循环）
  fall / 下落 / drop       下坠中（循环）
  walk / 走路 / move       自己溜达（循环）
  sleep / 睡觉 / rest      睡着（循环）

没提供的动画会自动回退：drag/fall -> idle，walk -> idle，sleep -> idle，
blink/happy -> idle。也就是说最少你只要给一段 idle 就能跑。

转换时会做两件事：
  1. 把 GIF/WebP/APNG 逐帧拆成 PNG；
  2. 所有帧统一补齐到同一个画布，内容按「底边居中」对齐，
     这样不同动画之间宠物不会上下乱跳。
"""

import argparse
import json
import os
import re
import shutil
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = os.path.join(HERE, "..", "assets-incoming")
DEFAULT_OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "pet")

IMG_EXT = {".png", ".gif", ".webp", ".apng", ".bmp"}

ALIASES = {
    "idle": "idle", "待机": "idle", "stand": "idle", "default": "idle", "静止": "idle",
    "blink": "blink", "眨眼": "blink",
    "happy": "happy", "开心": "happy", "joy": "happy", "click": "happy", "高兴": "happy",
    "drag": "drag", "被拖": "drag", "hold": "drag", "grab": "drag", "拖拽": "drag",
    "fall": "fall", "下落": "fall", "drop": "fall", "掉落": "fall",
    "walk": "walk", "走路": "walk", "move": "walk", "run": "walk", "溜达": "walk",
    "sleep": "sleep", "睡觉": "sleep", "rest": "sleep", "睡": "sleep",
}

DEFAULTS = {
    "idle": (8, True),
    "blink": (12, False),
    "happy": (12, False),
    "drag": (8, True),
    "fall": (10, True),
    "walk": (10, True),
    "sleep": (5, True),
}


def natural_key(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def slugify(name):
    name = name.strip()
    out = re.sub(r"[^0-9A-Za-z_\-]+", "_", name)
    out = out.strip("_")
    return out.lower() or "skin"


def anim_of(stem):
    """把文件名前缀映射成标准动画名。"""
    key = stem.strip().lower()
    key = re.sub(r"[_\-\s]*\d+$", "", key)          # idle_00 -> idle
    if key in ALIASES:
        return ALIASES[key]
    for alias, canon in ALIASES.items():
        if alias and alias in key:
            return canon
    return None


def load_frames(path):
    """把一个图片文件读成若干 RGBA 帧（动图会全部拆开）。"""
    im = Image.open(path)
    frames = []
    try:
        count = getattr(im, "n_frames", 1)
    except Exception:
        count = 1
    for i in range(count):
        try:
            im.seek(i)
        except EOFError:
            break
        frames.append(im.convert("RGBA").copy())
    if not frames:
        frames.append(im.convert("RGBA").copy())
    return frames


def collect(src):
    """返回 {动画名: [(排序键, 文件路径)]}。"""
    if not os.path.isdir(src):
        return {}, []

    entries = sorted(os.listdir(src), key=natural_key)
    subdirs = [e for e in entries if os.path.isdir(os.path.join(src, e))]
    files = [e for e in entries if os.path.splitext(e)[1].lower() in IMG_EXT]

    root = src
    if subdirs and not files:
        # 只有一个子文件夹就当成皮肤目录
        candidate = os.path.join(src, subdirs[0])
        inner = os.listdir(candidate)
        if len(subdirs) == 1 or all(
            os.path.isdir(os.path.join(candidate, x)) or
            os.path.splitext(x)[1].lower() in IMG_EXT for x in inner
        ):
            root = candidate

    groups = {}
    skipped = []

    for entry in sorted(os.listdir(root), key=natural_key):
        full = os.path.join(root, entry)

        if os.path.isdir(full):
            canon = anim_of(entry)
            if not canon:
                skipped.append(entry + "/")
                continue
            for f in sorted(os.listdir(full), key=natural_key):
                if os.path.splitext(f)[1].lower() in IMG_EXT:
                    groups.setdefault(canon, []).append(
                        (natural_key(f), os.path.join(full, f))
                    )
            continue

        ext = os.path.splitext(entry)[1].lower()
        if ext not in IMG_EXT:
            continue
        canon = anim_of(os.path.splitext(entry)[0])
        if not canon:
            skipped.append(entry)
            continue
        groups.setdefault(canon, []).append(
            (natural_key(entry), os.path.join(root, entry))
        )

    return groups, skipped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC, help="素材来源目录")
    ap.add_argument("--out", default=DEFAULT_OUT, help="assets/pet 输出目录")
    ap.add_argument("--name", default="my", help="皮肤名（也是 assets 下的文件夹名）")
    ap.add_argument("--keep", action="store_true", help="输出目录已存在时不删除旧内容")
    args = ap.parse_args()

    src = os.path.abspath(args.src)
    out_root = os.path.abspath(args.out)
    skin = slugify(args.name)

    groups, skipped = collect(src)

    if not groups:
        print("没找到可用素材。")
        print("请把 GIF / PNG 序列放进：", src)
        print("文件名用 idle / walk / happy 之类的前缀，例如 idle.gif、happy_00.png。")
        return 2

    if "idle" not in groups:
        print("警告：没有 idle（待机）动画，App 会拿第一个可用动画当待机。")

    # 统一画布尺寸
    loaded = {}
    canvas_w = canvas_h = 0
    for canon, items in groups.items():
        frames = []
        for _, path in sorted(items, key=lambda x: x[0]):
            try:
                frames.extend(load_frames(path))
            except Exception as e:
                print("  跳过 %s：%s" % (os.path.basename(path), e))
        if not frames:
            continue
        loaded[canon] = frames
        for f in frames:
            canvas_w = max(canvas_w, f.width)
            canvas_h = max(canvas_h, f.height)

    if not loaded:
        print("素材都读不出来，放弃。")
        return 2

    out_dir = os.path.join(out_root, skin)
    if os.path.isdir(out_dir) and not args.keep:
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    manifest = {
        "name": args.name.strip() or skin,
        "frameWidth": canvas_w,
        "frameHeight": canvas_h,
        "animations": {},
    }

    order = ["idle", "blink", "happy", "drag", "fall", "walk", "sleep"]
    names = [n for n in order if n in loaded] + [n for n in loaded if n not in order]

    total = 0
    for canon in names:
        frames = loaded[canon]
        d = os.path.join(out_dir, canon)
        os.makedirs(d, exist_ok=True)
        rels = []
        for i, f in enumerate(frames):
            canvas = Image.new("RGBA", (canvas_w, canvas_h), (0, 0, 0, 0))
            # 底边居中：保证脚底位置一致
            px = (canvas_w - f.width) // 2
            py = canvas_h - f.height
            canvas.alpha_composite(f, (px, py))
            rel = "%s/%02d.png" % (canon, i)
            canvas.save(os.path.join(out_dir, rel.replace("/", os.sep)))
            rels.append(rel)
            total += 1

        fps, loop = DEFAULTS.get(canon, (12, True))
        manifest["animations"][canon] = {"fps": fps, "loop": loop, "frames": rels}
        print("  %-6s %2d 帧  fps=%d loop=%s" % (canon, len(frames), fps, loop))

    with open(os.path.join(out_dir, "pet.json"), "w", encoding="utf-8") as fp:
        json.dump(manifest, fp, ensure_ascii=False, indent=2)

    print()
    print("皮肤名 :", skin)
    print("画布   : %dx%d" % (canvas_w, canvas_h))
    print("总帧数 :", total)
    print("输出   :", out_dir)
    if skipped:
        print("没认出来的（已忽略）:", ", ".join(skipped[:12]))
    print()
    print("接下来：重新打包 APK，在 App 的「角色」下拉框里选它。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
