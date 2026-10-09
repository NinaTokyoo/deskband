"""把 pet-assets 的素材整理进 Android assets，并生成 pet.json 清单

产物：
  app/src/main/assets/pet/<角色>/corner.png   右下角 / 左下角（正立，贴角）
  app/src/main/assets/pet/<角色>/border.png   右边框 / 左边框（正立，渲染时旋转）
  app/src/main/assets/pet/<角色>/pet.json     尺寸清单

不再裁切、不再改比例，只做原样拷贝（PNG 直接复制，避免二次编码损失）。
"""
import json
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(r"D:\dsh\dsh work\pet-app\pet-assets")
DST_ROOT = Path(r"D:\dsh\dsh work\pet-app\app\src\main\assets\pet")

NAMES = ["伊地知虹夏", "后藤一里", "喜多郁代", "山田凉", "广井菊里"]


def main():
    total = 0
    for name in NAMES:
        out = DST_ROOT / name
        out.mkdir(parents=True, exist_ok=True)

        corner = ROOT / "右下角" / f"{name}.png"
        border = ROOT / "右边框" / f"{name}.png"
        if not corner.exists() or not border.exists():
            print(f"!! {name} 缺素材，跳过")
            continue

        shutil.copyfile(corner, out / "corner.png")
        shutil.copyfile(border, out / "border.png")

        cw, ch = Image.open(corner).size
        bw, bh = Image.open(border).size
        meta = {
            "name": name,
            "corner": {"file": "corner.png", "w": cw, "h": ch},
            "border": {"file": "border.png", "w": bw, "h": bh},
            "borderRotationDeg": 90,
        }
        (out / "pet.json").write_text(
            json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        total += 1
        print(f"  {name}: corner {cw}x{ch}  border {bw}x{bh}")

    print(f"\n完成 {total} 个角色 -> {DST_ROOT}")
    for p in sorted(DST_ROOT.rglob("*")):
        if p.is_file():
            print(f"  {p.relative_to(DST_ROOT)}  {p.stat().st_size//1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
