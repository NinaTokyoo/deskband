# -*- coding: utf-8 -*-
"""把用户给的四张图变成 Android 资源（含按密度真缩放）。

用户要求：
  · 前两张（带透明圆角的 623x698 / 628x704）作为**浅色 / 深色模式的开屏图**
  · 后两张（1254x1254 满幅）作为**浅色 / 深色模式的应用图标**，默认图标 = 图三（浅色）

⚠️ 两个已经踩过的坑：
  1. 用户附件是 **WebP**（我最初按 .png 存了进去），AAPT 会直接报
     "failed to read PNG signature"。所以这里一律经 PIL 重存为真 PNG。
  2. 图标"分深浅"用的是 Android 官方的 **night 资源限定符**（drawable-night/），
     即跟随**系统**深色模式。启动器画图标时不会去读我们 App 的 SharedPreferences，
     系统级切图标只有这一条官方途径 —— 所以 App 内选"深色"不会换图标（做不到）。
"""
import os

from PIL import Image

SRC = "C:/Users/13793/.dsh/attachments/v1/objects"
ICON = {
    "": SRC + "/74/7407c12a02a378ff3cdf4128807812c802d4e35afb4e4385257ac027feb4cd84",
    "-night": SRC + "/bc/bc66b9eefdee791931a75fb4f21952179b45ee780e184186eeaa45a069dcde95",
}
SPLASH = {
    "": SRC + "/04/047c840bee5001468e18fe84f315c7526995c8b6530325786a49f4c75503b05d",
    "-night": SRC + "/19/19f61a20aee6d8dba157e77c4dfd36b5dcbe5def627e7fdcc659713f67d49260",
}

RES = "app/src/main/res"
# 开屏图在 160dpi(mdpi) 下的显示高度；再按密度倍数缩放
SPLASH_H_DP = 300
LEGACY_DP = 48          # 老式静态启动图标边长
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def save_png(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, "PNG", optimize=True)


def main():
    # ---- 图标画：满幅方图，交给 adaptive icon 当 background ----
    # nodpi：不让系统再按密度缩放（尺寸由 adaptive icon 自己定）
    for suffix, src in ICON.items():
        im = Image.open(src).convert("RGB")
        save_png(im, "%s/drawable%s-nodpi/ic_launcher_art.png" % (RES, suffix))
        # 老式静态图标兜底（minSdk=26 其实用不到，部分第三方启动器会找）
        for d, f in DENSITIES.items():
            px = int(round(LEGACY_DP * f))
            small = im.resize((px, px), Image.LANCZOS)
            save_png(small, "%s/mipmap%s-%s/ic_launcher.png" % (RES, suffix, d))
            save_png(small, "%s/mipmap%s-%s/ic_launcher_round.png" % (RES, suffix, d))
    print("图标画：drawable{,-night}-nodpi/ + mipmap{,-night}-{密度}/")

    # ---- 开屏图：按密度真缩放 ----
    for suffix, src in SPLASH.items():
        base = Image.open(src).convert("RGBA")
        ratio = base.height / base.width
        for d, f in DENSITIES.items():
            h = int(round(SPLASH_H_DP * f))
            w = int(round(h / ratio))
            save_png(base.resize((w, h), Image.LANCZOS),
                     "%s/drawable%s-%s/splash_logo.png" % (RES, suffix, d))
    print("开屏图：drawable{,-night}-{密度}/splash_logo.png（按密度缩放）")

    # ---- 清理：早期版本在 drawable-nodpi 落过多余的副本 ----
    for p in ("splash_light.png", "splash_dark.png",
              "ic_launcher_art_light.png", "ic_launcher_art_dark.png"):
        f = "%s/drawable-nodpi/%s" % (RES, p)
        if os.path.exists(f):
            os.remove(f)
            print("已清理多余副本 %s" % f)


if __name__ == "__main__":
    main()
