"""把 pet-assets 的素材整理进 Android assets，并生成 pet.json 清单

用户最终确定的素材结构（**只有两个源文件夹**）：

  pet-assets/右下角/<角色>.png    角落素材（正立，头朝上）
  pet-assets/正立素材/<角色>.png  正立素材（正立，头朝上），左右共用同一套

左侧不再单独出图 —— 实测过：
  flip(mirror(右下角))   == 原来的「左下角」 （差异 0.00）
  flip(mirror(正立素材)) == 原来的「左边框」 （差异 0.00）
所以左下角/左边框完全能在渲染时由镜像+上下翻转得到，不用占两份体积。

产物：
  app/src/main/assets/pet/<角色>/corner.png   右下角素材
  app/src/main/assets/pet/<角色>/upright.png  正立素材
  app/src/main/assets/pet/<角色>/pet.json     尺寸清单

不裁切、不改比例，PNG 直接复制（避免二次编码损失）。
"""
import json
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
DST_ROOT = Path(r"D:\dsh\dsh work\pet-app\app\src\main\assets\pet")

CORNER_DIR = "右下角"
UPRIGHT_DIR = "正立素材"


def main():
    names = sorted(p.stem for p in (ROOT / CORNER_DIR).glob("*.png"))
    if not names:
        print(f"!! {ROOT / CORNER_DIR} 里没有 PNG")
        return 1

    # 清掉旧的入库结果，避免残留 default/ 之类
    if DST_ROOT.exists():
        for d in DST_ROOT.iterdir():
            if d.is_dir():
                shutil.rmtree(d)

    total = 0
    for name in names:
        corner = ROOT / CORNER_DIR / f"{name}.png"
        upright = ROOT / UPRIGHT_DIR / f"{name}.png"
        if not upright.exists():
            print(f"!! {name} 缺少正立素材，跳过")
            continue

        out = DST_ROOT / name
        out.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(corner, out / "corner.png")
        shutil.copyfile(upright, out / "upright.png")

        cw, ch = Image.open(corner).size
        uw, uh = Image.open(upright).size
        meta = {
            "name": name,
            "corner": {"file": "corner.png", "w": cw, "h": ch},
            "upright": {"file": "upright.png", "w": uw, "h": uh},
        }
        (out / "pet.json").write_text(
            json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        total += 1
        print(f"  {name}: corner {cw}x{ch}  upright {uw}x{uh}")

    print(f"\n完成 {total} 个角色 -> {DST_ROOT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
